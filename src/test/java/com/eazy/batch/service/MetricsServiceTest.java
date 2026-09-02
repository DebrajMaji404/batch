package com.eazy.batch.service;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class MetricsServiceTest {

    @Test
    void whenDisabled_recordMethodsAreNoOps_evenWithRealRegistry() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsService metrics = new MetricsService(registry, false);

        metrics.recordItemProcessed("job1");
        metrics.recordItemSkipped("job1", "READ");
        metrics.recordJobDuration("job1", Duration.ofSeconds(1));
        metrics.recordJobSuccess("job1");
        metrics.recordJobFailure("job1");

        assertThat(registry.getMeters()).isEmpty();
    }

    @Test
    void whenEnabledButNoRegistry_recordMethodsAreNoOps_ratherThanThrowing() {
        // FIXED (earlier review): this used to be constructed with `new
        // MetricsService()`, which always left meterRegistry null. Verify a
        // null registry still degrades gracefully rather than NPEing.
        MetricsService metrics = new MetricsService(null, true);

        metrics.recordItemProcessed("job1");
        metrics.recordJobSuccess("job1");
        // No exception is the assertion here.
    }

    @Test
    void whenEnabledWithRegistry_recordItemProcessed_incrementsCounter() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsService metrics = new MetricsService(registry, true);

        metrics.recordItemProcessed("myJob");
        metrics.recordItemProcessed("myJob");
        metrics.recordItemProcessed("myJob");

        double count = registry.get("batch.items.processed").tag("job", "myJob").counter().count();
        assertThat(count).isEqualTo(3.0);
    }

    @Test
    void whenEnabledWithRegistry_recordItemSkipped_tagsByJobAndPhase() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsService metrics = new MetricsService(registry, true);

        metrics.recordItemSkipped("myJob", "READ");
        metrics.recordItemSkipped("myJob", "READ");
        metrics.recordItemSkipped("myJob", "WRITE");

        double readSkips = registry.get("batch.items.skipped")
                .tag("job", "myJob").tag("phase", "READ").counter().count();
        double writeSkips = registry.get("batch.items.skipped")
                .tag("job", "myJob").tag("phase", "WRITE").counter().count();

        assertThat(readSkips).isEqualTo(2.0);
        assertThat(writeSkips).isEqualTo(1.0);
    }

    @Test
    void whenEnabledWithRegistry_recordJobSuccessAndFailure_incrementSeparateCounters() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsService metrics = new MetricsService(registry, true);

        metrics.recordJobSuccess("myJob");
        metrics.recordJobFailure("myJob");
        metrics.recordJobFailure("myJob");

        assertThat(registry.get("batch.job.success").tag("job", "myJob").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("batch.job.failure").tag("job", "myJob").counter().count()).isEqualTo(2.0);
    }

    @Test
    void whenEnabledWithRegistry_recordJobDuration_recordsTimer() {
        MeterRegistry registry = new SimpleMeterRegistry();
        MetricsService metrics = new MetricsService(registry, true);

        metrics.recordJobDuration("myJob", Duration.ofMillis(500));

        long count = registry.get("batch.job.duration").tag("job", "myJob").timer().count();
        assertThat(count).isEqualTo(1);
    }
}
