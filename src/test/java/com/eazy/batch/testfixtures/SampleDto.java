package com.eazy.batch.testfixtures;

import com.poiji.annotation.ExcelCellName;
import lombok.Data;

/**
 * Minimal DTO used only to exercise {@code @BatchJob} code generation in
 * tests - not part of the library's public API.
 */
@Data
public class SampleDto {

    @ExcelCellName("name")
    private String name;

    @ExcelCellName("age")
    private Integer age;
}
