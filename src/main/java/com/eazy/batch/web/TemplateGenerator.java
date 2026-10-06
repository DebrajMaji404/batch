package com.eazy.batch.web;

import com.eazy.batch.enums.FileType;
import com.poiji.annotation.ExcelCellName;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a ready-to-fill upload template from a job's DTO: one header per
 * {@code @ExcelCellName} field (declared order, exactly what the readers validate), one sample
 * row from {@code @ExcelSampleData}, and an "Instructions" sheet listing each column's type,
 * whether it is required and its example.
 */
public final class TemplateGenerator {

    public record Column(String header, String type, boolean required, String sample, String allowed) {
    }

    private TemplateGenerator() {
    }

    public static List<Column> columns(Class<?> dto) {
        List<Column> columns = new ArrayList<>();
        for (Field field : dto.getDeclaredFields()) {
            ExcelCellName name = field.getAnnotation(ExcelCellName.class);
            if (name == null) continue;
            String allowed = field.getType().isEnum() ? enumValues(field.getType()) : "";
            String sample = stringAttribute(field, "ExcelSampleData", "value");
            if ((sample == null || sample.isBlank()) && !allowed.isEmpty()) {
                sample = allowed.split(", ")[0];
            }
            columns.add(new Column(name.value(), field.getType().getSimpleName(), isRequired(field),
                    sample == null ? "" : sample, allowed));
        }
        return columns;
    }

    public static byte[] generate(Class<?> dto, FileType fileType, String sheetName) {
        List<Column> columns = columns(dto);
        return fileType == FileType.CSV ? csv(columns) : excel(columns, sheetName);
    }

    public static String contentType(FileType fileType) {
        return fileType == FileType.CSV ? "text/csv"
                : "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    }

    public static String extension(FileType fileType) {
        return fileType == FileType.CSV ? "csv" : "xlsx";
    }

    private static byte[] excel(List<Column> columns, String sheetName) {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Font bold = wb.createFont();
            bold.setBold(true);
            CellStyle head = wb.createCellStyle();
            head.setFont(bold);

            Sheet data = wb.createSheet(sheetName == null || sheetName.isBlank() ? "Data" : sheetName);
            Row header = data.createRow(0);
            Row sample = data.createRow(1);
            for (int i = 0; i < columns.size(); i++) {
                Cell h = header.createCell(i);
                h.setCellValue(columns.get(i).header());
                h.setCellStyle(head);
                sample.createCell(i).setCellValue(columns.get(i).sample());
                data.setColumnWidth(i, Math.min(60, Math.max(14, columns.get(i).header().length() + 4)) * 256);
            }

            Sheet help = wb.createSheet("Instructions");
            Row hr = help.createRow(0);
            String[] titles = {"Column", "Required", "Type", "Example", "Allowed values"};
            for (int i = 0; i < titles.length; i++) {
                Cell c = hr.createCell(i);
                c.setCellValue(titles[i]);
                c.setCellStyle(head);
                help.setColumnWidth(i, 28 * 256);
            }
            int r = 1;
            for (Column c : columns) {
                Row row = help.createRow(r++);
                row.createCell(0).setCellValue(c.header());
                row.createCell(1).setCellValue(c.required() ? "Yes" : "No");
                row.createCell(2).setCellValue(c.type());
                row.createCell(3).setCellValue(c.sample());
                row.createCell(4).setCellValue(c.allowed());
            }
            Row note = help.createRow(r + 1);
            note.createCell(0).setCellValue("Delete the example row on the first sheet, then fill in your data. Keep the header row unchanged.");

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the template: " + e.getMessage(), e);
        }
    }

    private static byte[] csv(List<Column> columns) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < columns.size(); i++) sb.append(i > 0 ? "," : "").append(quote(columns.get(i).header()));
        sb.append("\r\n");
        for (int i = 0; i < columns.size(); i++) sb.append(i > 0 ? "," : "").append(quote(columns.get(i).sample()));
        sb.append("\r\n");
        return sb.toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String quote(String s) {
        return "\"" + (s == null ? "" : s.replace("\"", "\"\"")) + "\"";
    }

    private static String enumValues(Class<?> type) {
        Object[] constants = type.getEnumConstants();
        List<String> names = new ArrayList<>();
        if (constants != null) for (Object o : constants) names.add(String.valueOf(o));
        return String.join(", ", names);
    }

    /** Required = Poiji {@code mandatoryCell=true} (read reflectively, version-proof) or a bean-validation not-null/blank. */
    private static boolean isRequired(Field field) {
        for (Annotation a : field.getAnnotations()) {
            String n = a.annotationType().getSimpleName();
            if (n.equals("NotNull") || n.equals("NotBlank") || n.equals("NotEmpty")) return true;
            try {
                Method m = a.annotationType().getMethod("mandatoryCell");
                if (Boolean.TRUE.equals(m.invoke(a))) return true;
            } catch (ReflectiveOperationException ignored) {
                // not a Poiji annotation with that attribute
            }
        }
        return false;
    }

    private static String stringAttribute(Field field, String annotationSimpleName, String attribute) {
        for (Annotation a : field.getAnnotations()) {
            if (!a.annotationType().getSimpleName().equals(annotationSimpleName)) continue;
            try {
                Object v = a.annotationType().getMethod(attribute).invoke(a);
                return v == null ? null : v.toString();
            } catch (ReflectiveOperationException ignored) {
                return null;
            }
        }
        return null;
    }
}
