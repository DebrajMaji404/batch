package com.eazy.batch.web;

import com.eazy.batch.test.BatchJobTester;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BatchJobTesterTest {

    @Test
    void buildsExcelAndCsvFixtures() {
        byte[] xlsx = BatchJobTester.excel(List.of("a", "b"), List.of(List.of("x", 2)));
        assertThat(xlsx).isNotEmpty();
        String csv = new String(BatchJobTester.csv(List.of("a", "b"), List.of(List.of("x", 2))));
        assertThat(csv).isEqualTo("a,b\n\"x\",\"2\"\n");
    }
}
