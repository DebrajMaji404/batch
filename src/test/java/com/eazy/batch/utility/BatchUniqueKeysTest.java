package com.eazy.batch.utility;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BatchUniqueKeysTest {

    static class Row {
        String code = " AB-1 ";
        Integer n = 3;
    }

    static class Child extends Row {
    }

    @Test
    void keyIsNormalisedAndReadsInheritedFields() {
        assertThat(BatchUniqueKeys.keyOf(new Child(), new String[]{"code", "n"})).isEqualTo("ab-1 | 3");
    }

    @Test
    void missingOrBlankFieldMeansNoKey() {
        assertThat(BatchUniqueKeys.keyOf(new Row(), new String[]{"nope"})).isNull();
    }

    @Test
    void outsideARunNothingIsFlagged() {
        assertThat(BatchUniqueKeys.check(new Row(), "code")).isNull();
    }
}
