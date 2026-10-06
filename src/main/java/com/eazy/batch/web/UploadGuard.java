package com.eazy.batch.web;

import com.eazy.batch.enums.FileType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * Rejects an upload before a job is launched: wrong extension, over the size limit, or more
 * data rows than {@code eazy.batch.upload.max-rows}. Throws {@link UploadRejectedException},
 * whose message is safe to show to the user.
 */
public final class UploadGuard {

    private final long maxBytes;
    private final int maxRows;

    /** @param maxFileSizeMb 0 = unlimited; @param maxRows 0 = unlimited */
    public UploadGuard(long maxFileSizeMb, int maxRows) {
        this.maxBytes = maxFileSizeMb <= 0 ? 0 : maxFileSizeMb * 1024 * 1024;
        this.maxRows = Math.max(0, maxRows);
    }

    public void checkName(String originalFileName, FileType type) {
        if (originalFileName == null || originalFileName.isBlank()) {
            throw new UploadRejectedException("The uploaded file has no name.");
        }
        String name = originalFileName.toLowerCase(Locale.ROOT);
        boolean ok = switch (type) {
            case CSV -> name.endsWith(".csv");
            case EXCEL -> name.endsWith(".xlsx") || name.endsWith(".xls");
            case JSON -> name.endsWith(".json");
            case XML -> name.endsWith(".xml");
        };
        if (!ok) {
            throw new UploadRejectedException("Unsupported file type for this job - expected " + type.getDescription() + ".");
        }
    }

    public void checkSize(long sizeBytes) {
        if (sizeBytes <= 0) throw new UploadRejectedException("The uploaded file is empty.");
        if (maxBytes > 0 && sizeBytes > maxBytes) {
            throw new UploadRejectedException("The file is larger than the allowed " + (maxBytes / 1024 / 1024) + " MB.");
        }
    }

    /** Counts data rows (header excluded) of an already stored file. */
    public void checkRows(Path file, FileType type, int sheetIndex, String sheetName) {
        if (maxRows <= 0) return;
        long rows = type == FileType.CSV ? csvRows(file) : type == FileType.EXCEL ? excelRows(file, sheetIndex, sheetName) : -1;
        if (rows > maxRows) {
            throw new UploadRejectedException("The file has " + rows + " rows; at most " + maxRows + " are allowed.");
        }
    }

    private long csvRows(Path file) {
        try (var lines = Files.lines(file)) {
            return Math.max(0, lines.filter(l -> !l.isBlank()).count() - 1);
        } catch (IOException | RuntimeException e) {
            return -1;
        }
    }

    private long excelRows(Path file, int sheetIndex, String sheetName) {
        try (InputStream in = Files.newInputStream(file); Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = sheetName != null && !sheetName.isBlank() ? wb.getSheet(sheetName) : wb.getSheetAt(sheetIndex);
            return sheet == null ? 0 : Math.max(0, sheet.getLastRowNum());
        } catch (IOException | RuntimeException e) {
            return -1; // unreadable here - let the job report the real problem
        }
    }

    public static class UploadRejectedException extends RuntimeException {
        public UploadRejectedException(String message) {
            super(message);
        }
    }
}
