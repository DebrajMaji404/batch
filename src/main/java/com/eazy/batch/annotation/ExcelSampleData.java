package com.eazy.batch.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Example value for a DTO column, written into the sample row of the generated upload
 * template ({@code GET {basePath}/{jobName}/template}) and its "Instructions" sheet.
 * Any annotation named {@code ExcelSampleData} with a {@code value()} is understood, so
 * an application that already has its own can keep using it.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.FIELD)
public @interface ExcelSampleData {
    String value();
}
