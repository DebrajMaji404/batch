package com.eazy.batch.reader;

import com.eazy.batch.annotation.ExcelDateFormat;
import com.eazy.batch.enums.FileType;
import com.poiji.annotation.ExcelCellName;
import lombok.Data;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.item.file.FlatFileParseException;
import org.springframework.core.io.FileSystemResource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StructuredFileTest {

    enum Level { LOW, HIGH }

    @Data
    public static class Row {
        @ExcelCellName("Student Name")
        private String name;
        @ExcelCellName("Age")
        private Integer age;
        @ExcelCellName("Level")
        private Level level;
        @ExcelCellName("Joined")
        @ExcelDateFormat(pattern = "dd/MM/yyyy")
        private LocalDate joined;
        @ExcelCellName("Active")
        private Boolean active;
    }

    // ─── record sources ──────────────────────────────────────────────

    private static List<RecordSource.Record> json(String text) throws IOException {
        List<RecordSource.Record> out = new ArrayList<>();
        try (JsonRecordSource src = new JsonRecordSource(new StringReader(text))) {
            for (RecordSource.Record r = src.next(); r != null; r = src.next()) out.add(r);
        }
        return out;
    }

    private static List<RecordSource.Record> xml(String text) throws IOException {
        List<RecordSource.Record> out = new ArrayList<>();
        try (XmlRecordSource src = new XmlRecordSource(new ByteArrayInputStream(text.getBytes()))) {
            for (RecordSource.Record r = src.next(); r != null; r = src.next()) out.add(r);
        }
        return out;
    }

    @Test
    void jsonArrayAndWrapperObject() throws IOException {
        assertThat(json("[{\"a\":1,\"b\":\"x\\\"y\",\"c\":null},{\"a\":true}]")).hasSize(2);
        assertThat(json("[{\"a\":1,\"b\":\"x\\\"y\",\"c\":null}]").get(0).values())
                .containsEntry("a", "1").containsEntry("b", "x\"y").containsEntry("c", null);
        assertThat(json("{\"meta\":{\"n\":[1]},\"rows\":[{\"a\":1},{\"a\":2}]}")).hasSize(2);
        assertThat(json("[]")).isEmpty();
    }

    @Test
    void jsonNestedValueOnlyBreaksItsOwnRecord() throws IOException {
        List<RecordSource.Record> records = json("[{\"a\":{\"b\":1}},{\"c\":2}]");
        assertThat(records.get(0).error()).contains("nested");
        assertThat(records.get(1).error()).isNull();
        assertThat(records.get(1).values()).containsEntry("c", "2");
    }

    @Test
    void jsonSyntaxErrorsAreReported() {
        assertThatThrownBy(() -> json("[{\"a\":1} {\"a\":2}]")).hasMessageContaining("Invalid JSON");
        assertThatThrownBy(() -> json("{\"x\":1}")).hasMessageContaining("array of records");
    }

    @Test
    void xmlChildrenAndAttributes() throws IOException {
        List<RecordSource.Record> records = xml(
                "<rows><row><name>Asha</name><age>21</age></row><row id=\"7\"><name><![CDATA[B&]]></name></row></rows>");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).values()).containsEntry("name", "Asha").containsEntry("age", "21");
        assertThat(records.get(1).values()).containsEntry("id", "7").containsEntry("name", "B&");
    }

    @Test
    void xmlNestedElementOnlyBreaksItsOwnRecord() throws IOException {
        List<RecordSource.Record> records = xml("<rows><row><a><b>1</b></a></row><row><c>2</c></row></rows>");
        assertThat(records.get(0).error()).contains("contains other elements");
        assertThat(records.get(1).error()).isNull();
    }

    @Test
    void xmlExternalEntitiesAreNotResolved() {
        String evil = "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><rows><row><a>&e;</a></row></rows>";
        assertThatThrownBy(() -> xml(evil)).isInstanceOf(IOException.class);
    }

    // ─── reader ──────────────────────────────────────────────────────

    private StructuredFileItemReader<Row> open(Path file, FileType type) {
        StructuredFileItemReader<Row> reader = new StructuredFileItemReader<>(new FileSystemResource(file), Row.class, type);
        reader.open(new ExecutionContext());
        return reader;
    }

    @Test
    void jsonReaderMapsLooselyNamedFieldsAndConvertsTypes(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.json");
        Files.writeString(file, """
                [
                  {"Student Name":"Asha","age":21,"LEVEL":"high","joined":"31/01/2025","active":"yes"},
                  {"studentName":"Ravi","Age":"30"}
                ]""");
        StructuredFileItemReader<Row> reader = open(file, FileType.JSON);

        Row first = reader.read();
        assertThat(first.getName()).isEqualTo("Asha");
        assertThat(first.getAge()).isEqualTo(21);
        assertThat(first.getLevel()).isEqualTo(Level.HIGH);
        assertThat(first.getJoined()).isEqualTo(LocalDate.of(2025, 1, 31));
        assertThat(first.getActive()).isTrue();
        assertThat(reader.getLastRowNumber()).isEqualTo(1);

        Row second = reader.read();
        assertThat(second.getName()).isEqualTo("Ravi");
        assertThat(second.getAge()).isEqualTo(30);
        assertThat(reader.getLastRowNumber()).isEqualTo(2);

        assertThat(reader.read()).isNull();
        reader.close();
    }

    @Test
    void xmlReaderReadsRecords(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.xml");
        Files.writeString(file, "<rows><row><Student_Name>Asha</Student_Name><Age>21</Age></row></rows>");
        StructuredFileItemReader<Row> reader = open(file, FileType.XML);
        Row row = reader.read();
        assertThat(row.getName()).isEqualTo("Asha");
        assertThat(row.getAge()).isEqualTo(21);
        assertThat(reader.read()).isNull();
    }

    @Test
    void badRecordIsRejectedAndReadingContinues(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.json");
        Files.writeString(file, "[{\"age\":\"abc\"},{\"nmae\":\"typo\"},{\"age\":5}]");
        StructuredFileItemReader<Row> reader = open(file, FileType.JSON);

        assertThatThrownBy(reader::read).isInstanceOf(FlatFileParseException.class)
                .hasMessageContaining("record 1").hasMessageContaining("Integer");
        assertThatThrownBy(reader::read).isInstanceOf(FlatFileParseException.class)
                .hasMessageContaining("unknown field").hasMessageContaining("nmae");
        assertThat(reader.read().getAge()).isEqualTo(5);
        assertThat(reader.getLastRowNumber()).isEqualTo(3);
    }

    @Test
    void malformedFileEndsTheDataAfterOneFailure(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.json");
        Files.writeString(file, "[{\"age\":1},{\"age\":");
        StructuredFileItemReader<Row> reader = open(file, FileType.JSON);

        assertThat(reader.read().getAge()).isEqualTo(1);
        assertThatThrownBy(reader::read).isInstanceOf(FlatFileParseException.class);
        assertThat(reader.read()).isNull(); // must not loop on the same error
    }

    @Test
    void restartSkipsAlreadyProcessedRecords(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.json");
        Files.writeString(file, "[{\"age\":1},{\"age\":2},{\"age\":3}]");
        StructuredFileItemReader<Row> reader = new StructuredFileItemReader<>(new FileSystemResource(file), Row.class, FileType.JSON);
        ExecutionContext context = new ExecutionContext();
        context.putLong("structured.reader.record.index", 2);
        reader.open(context);

        assertThat(reader.read().getAge()).isEqualTo(3);
        assertThat(reader.getLastRowNumber()).isEqualTo(3);
    }

    @Test
    void countsRecords(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("a.xml");
        Files.writeString(file, "<rows><row><a>1</a></row><row><a>2</a></row></rows>");
        assertThat(StructuredFileItemReader.countRecords(file, FileType.XML)).isEqualTo(2);
        assertThat(StructuredFileItemReader.countRecords(dir.resolve("missing.json"), FileType.JSON)).isEqualTo(-1);
    }
}
