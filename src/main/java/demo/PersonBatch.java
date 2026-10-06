package demo;

import lombok.Data;

import java.util.List;

/** What one row turns into (here just the same rows; use your entities in a real project). */
@Data
public class PersonBatch {
    private final List<PersonRow> rows;
}
