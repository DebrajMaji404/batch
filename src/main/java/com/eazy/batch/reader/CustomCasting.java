/*
 *  #=============================================================================
 *  *  # Copyright (c) 2025 EBest Solutions Pvt. Ltd. All rights reserved.
 *  *  #
 *  *  # This software is furnished under a license and may be used and copied
 *  *  # only in accordance with the terms of such license and with the
 *  *  # inclusion of the above copyright notice. This software or any other
 *  *  # copies thereof may not be provided or otherwise made available to any
 *  *  # other person. No title to and ownership of the software is hereby transferred.
 *  *  #
 *  *  # The information in this software is subject to change without notice
 *  *  # and should not be construed as a commitment by EBest Solutions Pvt. Ltd.
 *  *  # EBest Solutions assumes no responsibility for the use or reliability of its
 *  *  # software on equipment, which is not supplied by EBest Solutions Pvt. Ltd.
 *  *  #=============================================================================
 */

package com.eazy.batch.reader;

import com.poiji.config.Casting;
import com.poiji.config.DefaultCasting;
import com.poiji.exception.PoijiException;
import com.poiji.option.PoijiOptions;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Field;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.Optional;

public class CustomCasting implements Casting {

    private final DateTimeFormatter dateFormatter;
    private final DefaultCasting defaultCasting;

    public CustomCasting(String datePattern) {
        this.dateFormatter = DateTimeFormatter.ofPattern(datePattern);
        this.defaultCasting = new DefaultCasting();
    }

    @Override
    public Object castValue(Field field, String value, int row, int column, PoijiOptions options) {
        if (isNullOrBlank(value)) {
            return null;
        }

        var trimmedValue = value.trim();
        var type = field.getType();

        if (type.equals(LocalDate.class)) {
            return parseLocalDate(trimmedValue, row, column);
        }

        if (type.isEnum()) {
            return parseEnum(type, trimmedValue, row, column);
        }

        return defaultCasting.castValue(field, value, row, column, options);
    }

    private boolean isNullOrBlank(String value) {
        return value == null || value.isBlank();
    }

    private @NotNull LocalDate parseLocalDate(String value, int row, int column) {
        try {
            return LocalDate.parse(value, dateFormatter);
        } catch (DateTimeParseException e) {
            throw new PoijiException(
                    "Failed to parse date '%s' at row %d, column %d with pattern: %s"
                            .formatted(value, row, column, dateFormatter)
            );
        }
    }

    private Object parseEnum(Class<?> enumType, String value, int row, int column) {
        return tryFromDisplayName(enumType, value)
                .or(() -> tryValueOf(enumType, value))
                .or(() -> tryCaseInsensitiveMatch(enumType, value))
                .orElseThrow(() -> new PoijiException(
                        "Cannot convert '%s' to enum %s at row %d, column %d"
                                .formatted(value, enumType.getSimpleName(), row, column)
                ));
    }

    private Optional<Object> tryFromDisplayName(Class<?> enumType, String value) {
        try {
            var method = enumType.getMethod("fromDisplayName", String.class);
            return Optional.ofNullable(method.invoke(null, value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Object> tryValueOf(Class<?> enumType, String value) {
        try {
            var method = enumType.getMethod("valueOf", String.class);
            try {
                return Optional.ofNullable(method.invoke(null, value));
            } catch (Exception e) {
                var normalizedValue = value.toUpperCase().replace(" ", "_");
                return Optional.ofNullable(method.invoke(null, normalizedValue));
            }
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private Optional<Object> tryCaseInsensitiveMatch(@NotNull Class<?> enumType, String value) {
        return Arrays.stream(enumType.getEnumConstants())
                .filter(constant -> constant.toString().equalsIgnoreCase(value))
                .findFirst()
                .map(Object.class::cast);
    }
}