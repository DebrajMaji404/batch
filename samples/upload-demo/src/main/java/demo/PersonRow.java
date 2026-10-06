package demo;

import com.eazy.batch.annotation.ExcelSampleData;
import com.poiji.annotation.ExcelCellName;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** One row of the uploaded file. */
@Data
public class PersonRow {

    @ExcelCellName("Email")
    @ExcelSampleData("asha@example.com")
    @NotBlank
    private String email;

    @ExcelCellName("Name")
    @ExcelSampleData("Asha")
    @NotBlank
    private String name;

    @ExcelCellName("Age")
    @ExcelSampleData("29")
    @Min(0)
    private Integer age;
}
