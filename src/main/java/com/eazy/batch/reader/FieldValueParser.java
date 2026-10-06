package com.eazy.batch.reader;

import com.eazy.batch.annotation.ExcelDateFormat;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Locale;

/**
 * Converts the text of one field of a JSON/XML record into the type of the DTO field.
 * Same rules as the CSV reader (dates via {@code @ExcelDateFormat}, enums by
 * {@code fromDisplayName}/name/case-insensitive match), plus a few more number types and a
 * strict boolean.
 */
final class FieldValueParser {

    private FieldValueParser() {
    }

    static Object parse(String value, Field field, String defaultDatePattern, String label) {
        Class<?> type = field.getType();
        try {
            if (type == String.class || type == Object.class) return value;
            if (type == Integer.class || type == int.class) return Integer.valueOf(value);
            if (type == Long.class || type == long.class) return Long.valueOf(value);
            if (type == Short.class || type == short.class) return Short.valueOf(value);
            if (type == Double.class || type == double.class) return Double.valueOf(value);
            if (type == Float.class || type == float.class) return Float.valueOf(value);
            if (type == BigDecimal.class) return new BigDecimal(value);
            if (type == BigInteger.class) return new BigInteger(value);
            if (type == Boolean.class || type == boolean.class) return bool(value);
            if (type == LocalDate.class) return LocalDate.parse(value, DateTimeFormatter.ofPattern(datePattern(field, defaultDatePattern)));
            if (type == LocalDateTime.class) return LocalDateTime.parse(value);
            if (type.isEnum()) return parseEnum(type, value);
            return value;
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "Cannot read '" + value + "' as " + type.getSimpleName() + " for '" + label + "': " + e.getMessage(), e);
        }
    }

    static String datePattern(Field field, String fallback) {
        ExcelDateFormat format = field.getAnnotation(ExcelDateFormat.class);
        return format != null ? format.pattern() : fallback;
    }

    private static Boolean bool(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "true", "yes", "y", "1" -> Boolean.TRUE;
            case "false", "no", "n", "0" -> Boolean.FALSE;
            default -> throw new IllegalArgumentException("expected true or false");
        };
    }

    private static Object parseEnum(Class<?> enumType, String value) {
        try {
            Object byDisplay = enumType.getMethod("fromDisplayName", String.class).invoke(null, value);
            if (byDisplay != null) return byDisplay;
        } catch (Exception ignored) {
            // no such method, or it rejected the value - try the plain ways
        }
        for (Object constant : enumType.getEnumConstants()) {
            String name = constant.toString();
            if (name.equalsIgnoreCase(value) || name.equalsIgnoreCase(value.replace(' ', '_'))
                    || ((Enum<?>) constant).name().equalsIgnoreCase(value.replace(' ', '_'))) {
                return constant;
            }
        }
        throw new IllegalArgumentException("valid values: " + Arrays.toString(enumType.getEnumConstants()));
    }
}
