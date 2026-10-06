package com.eazy.batch.report;

import com.eazy.batch.enums.FileType;
import com.eazy.batch.reader.CSVItemReader;
import com.eazy.batch.reader.ExcelItemReaderWithHeaderValidation;
import com.eazy.batch.reader.StructuredFileItemReader;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.ItemStreamReader;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.core.io.FileSystemResource;

import java.util.function.IntSupplier;

/**
 * Walks the uploaded file once more, row by row, with the same reader the job
 * used - so row numbers and parsing are guaranteed to match the run being
 * reported on. Nothing is tracked or recorded while doing so.
 */
final class SourceRows implements AutoCloseable {

    /** One row of the file. {@code dto} is null (and {@code parseError} set) when the row can't be parsed. */
    record SourceRow(int number, Object dto, String parseError) {
    }

    private final ItemStreamReader<Object> reader;
    private final IntSupplier lastRow;
    private int previousRow = 0;
    private int stalled = 0;

    @SuppressWarnings("unchecked")
    private SourceRows(ReportSpec spec, String filePath) {
        Class<Object> dtoClass = (Class<Object>) spec.dtoClass();
        FileSystemResource resource = new FileSystemResource(filePath);
        if (spec.fileType() == FileType.CSV) {
            CSVItemReader<Object> csv = new CSVItemReader<>(resource, dtoClass);
            this.reader = csv;
            this.lastRow = csv::getLastRowNumber;
        } else if (spec.fileType() == FileType.JSON || spec.fileType() == FileType.XML) {
            StructuredFileItemReader<Object> structured = new StructuredFileItemReader<>(resource, dtoClass, spec.fileType());
            this.reader = structured;
            this.lastRow = structured::getLastRowNumber;
        } else {
            ExcelItemReaderWithHeaderValidation<Object> excel = new ExcelItemReaderWithHeaderValidation<>(
                    resource, dtoClass, spec.sheetIndex(), spec.sheetName());
            this.reader = excel;
            this.lastRow = excel::getLastRowNumber;
        }
    }

    /** Opens the file. The caller must {@link #close()} the result. */
    static SourceRows open(ReportSpec spec, String filePath) {
        BatchRowTracker.pause();
        try {
            SourceRows rows = new SourceRows(spec, filePath);
            rows.reader.open(new ExecutionContext());
            return rows;
        } catch (RuntimeException e) {
            BatchRowTracker.resume();
            throw e;
        }
    }

    /** @return the next row, or {@code null} at the end of the file */
    SourceRow next() {
        try {
            Object dto = reader.read();
            if (dto == null) {
                return null;
            }
            previousRow = lastRow.getAsInt();
            stalled = 0;
            return new SourceRow(previousRow, dto, null);
        } catch (FlatFileParseException e) {
            int row = e.getLineNumber();
            // A reader that fails without advancing would loop forever.
            if (row <= previousRow) {
                if (++stalled > 3) {
                    return null;
                }
            } else {
                stalled = 0;
            }
            previousRow = Math.max(previousRow, row);
            return new SourceRow(row, null, e.getMessage());
        } catch (Exception e) {
            throw new IllegalStateException("Could not re-read the uploaded file: " + e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        try {
            reader.close();
        } finally {
            BatchRowTracker.resume();
        }
    }
}
