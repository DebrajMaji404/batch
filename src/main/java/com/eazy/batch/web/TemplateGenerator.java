package com.eazy.batch.web;

import com.eazy.batch.annotation.ExcelDateFormat;
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a ready-to-fill upload template from a job's DTO: one header per
 * {@code @ExcelCellName} field (declared order, exactly what the readers validate), one sample
 * row from {@code @ExcelSampleData}, and an "Instructions" sheet listing each column's type,
 * whether it is required and its example.
 */
public final class TemplateGenerator {

    /** How a value is written in a JSON template: as a bare number/boolean, or as a string. */
    public enum Kind { STRING, NUMBER, BOOLEAN }

    public record Column(String header, String type, boolean required, String sample, String allowed,
                         String format, Kind kind) {
    }

    private static final LocalDate SAMPLE_DATE = LocalDate.of(2025, 1, 31);

    private TemplateGenerator() {
    }

    public static List<Column> columns(Class<?> dto) {
        List<Column> columns = new ArrayList<>();
        for (Field field : dto.getDeclaredFields()) {
            ExcelCellName name = field.getAnnotation(ExcelCellName.class);
            if (name == null) continue;
            Class<?> type = field.getType();
            String allowed = type.isEnum() ? enumValues(type) : "";
            String format = type == LocalDate.class ? datePattern(field) : type == LocalDateTime.class ? "yyyy-MM-dd'T'HH:mm:ss" : "";

            String sample = stringAttribute(field, "ExcelSampleData", "value");
            if (sample == null || sample.isBlank()) sample = defaultSample(type, allowed, format);

            columns.add(new Column(name.value(), type.getSimpleName(), isRequired(field), sample, allowed, format, kindOf(type)));
        }
        return columns;
    }

    private static Kind kindOf(Class<?> t) {
        if (t == Boolean.class || t == boolean.class) return Kind.BOOLEAN;
        if (Number.class.isAssignableFrom(t) || (t.isPrimitive() && t != char.class)) return Kind.NUMBER;
        return Kind.STRING;
    }

    /** A value that parses for the field's type, so a template filled in as-is is valid. */
    private static String defaultSample(Class<?> t, String allowed, String format) {
        if (!allowed.isEmpty()) return allowed.split(", ")[0];
        if (t == LocalDate.class) return SAMPLE_DATE.format(DateTimeFormatter.ofPattern(format));
        if (t == LocalDateTime.class) return "2025-01-31T09:30:00";
        if (t == Boolean.class || t == boolean.class) return "true";
        if (t == Integer.class || t == int.class || t == Long.class || t == long.class
                || t == Short.class || t == short.class || t == BigInteger.class) return "1";
        if (t == Double.class || t == double.class || t == Float.class || t == float.class || t == BigDecimal.class) return "1.5";
        return "text";
    }

    private static String datePattern(Field field) {
        ExcelDateFormat f = field.getAnnotation(ExcelDateFormat.class);
        return f != null ? f.pattern() : "yyyy-MM-dd";
    }

    public static byte[] generate(Class<?> dto, FileType fileType, String sheetName) {
        List<Column> columns = columns(dto);
        return switch (fileType) {
            case CSV -> csv(columns);
            case JSON -> json(columns);
            case XML -> xml(columns);
            case EXCEL -> excel(columns, sheetName);
        };
    }

    public static String contentType(FileType fileType) {
        return switch (fileType) {
            case CSV -> "text/csv";
            case JSON -> "application/json";
            case XML -> "application/xml";
            case EXCEL -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        };
    }

    public static String extension(FileType fileType) {
        return switch (fileType) {
            case CSV -> "csv";
            case JSON -> "json";
            case XML -> "xml";
            case EXCEL -> "xlsx";
        };
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
            String[] titles = {"Column", "Required", "Type", "Example", "Allowed values", "Format"};
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
                row.createCell(5).setCellValue(c.format());
            }
            Row note = help.createRow(r + 1);
            note.createCell(0).setCellValue("Delete the example row on the first sheet, then fill in your data. Keep the header row unchanged.");

            wb.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Could not build the template: " + e.getMessage(), e);
        }
    }

    private static byte[] json(List<Column> columns) {
        StringBuilder sb = new StringBuilder("[\n  {\n");
        for (int i = 0; i < columns.size(); i++) {
            Column c = columns.get(i);
            String value = c.kind() == Kind.STRING || c.sample().isBlank() ? jsonString(c.sample()) : c.sample();
            sb.append("    ").append(jsonString(c.header())).append(": ").append(value)
                    .append(i < columns.size() - 1 ? ",\n" : "\n");
        }
        return sb.append("  }\n]\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] xml(List<Column> columns) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<rows>\n  <row>\n");
        for (Column c : columns) {
            String tag = xmlName(c.header());
            sb.append("    <").append(tag).append(">").append(xmlText(c.sample())).append("</").append(tag).append(">\n");
        }
        return sb.append("  </row>\n</rows>\n").toString().getBytes(StandardCharsets.UTF_8);
    }

    private static String jsonString(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (char ch : (s == null ? "" : s).toCharArray()) {
            switch (ch) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (ch < 0x20) sb.append(String.format("\\u%04x", (int) ch));
                    else sb.append(ch);
                }
            }
        }
        return sb.append('"').toString();
    }

    /** A header as an XML element name ("Student Name" -> "Student_Name"); the reader matches it back loosely. */
    static String xmlName(String header) {
        String name = (header == null ? "" : header).trim().replaceAll("[^A-Za-z0-9_.-]", "_");
        if (name.isEmpty() || !Character.isLetter(name.charAt(0)) && name.charAt(0) != '_') name = "_" + name;
        return name;
    }

    private static String xmlText(String s) {
        return (s == null ? "" : s).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
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
