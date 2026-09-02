package com.eazy.batch.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class ProgressTrackingServiceTest {

    @Test
    void constructor_withZeroInterval_fallsBackTo100_insteadOfDivideByZero() {
        // FIXED (earlier review): updateInterval=0 previously caused an
        // ArithmeticException in updateProgress()'s `% updateInterval` check.
        ProgressTrackingService service = new ProgressTrackingService(0);
        service.initProgress(1L, 1000);

        assertThatCode(() -> service.updateProgress(1L, 50))
                .doesNotThrowAnyException();
    }

    @Test
    void constructor_withNegativeInterval_fallsBackTo100() {
        ProgressTrackingService service = new ProgressTrackingService(-5);
        service.initProgress(1L, 1000);

        assertThatCode(() -> service.updateProgress(1L, 100))
                .doesNotThrowAnyException();
    }

    @Test
    void initProgress_thenGetProgress_returnsTrackedInfo() {
        ProgressTrackingService service = new ProgressTrackingService(10);

        service.initProgress(42L, 500);

        ProgressTrackingService.ProgressInfo info = service.getProgress(42L);
        assertThat(info).isNotNull();
        assertThat(info.totalItems).isEqualTo(500);
        assertThat(info.processedItems).isZero();
    }

    @Test
    void updateProgress_forUnknownJob_doesNothing() {
        ProgressTrackingService service = new ProgressTrackingService(10);

        assertThatCode(() -> service.updateProgress(999L, 50))
                .doesNotThrowAnyException();
        assertThat(service.getProgress(999L)).isNull();
    }

    @Test
    void updateProgress_updatesProcessedItemsCount() {
        ProgressTrackingService service = new ProgressTrackingService(10);
        service.initProgress(1L, 1000);

        service.updateProgress(1L, 250);

        assertThat(service.getProgress(1L).processedItems).isEqualTo(250);
    }

    @Test
    void completeProgress_removesTrackedJob() {
        ProgressTrackingService service = new ProgressTrackingService(10);
        service.initProgress(1L, 100);
        service.updateProgress(1L, 100);

        service.completeProgress(1L);

        assertThat(service.getProgress(1L)).isNull();
    }

    @Test
    void completeProgress_forUnknownJob_doesNothing() {
        ProgressTrackingService service = new ProgressTrackingService(10);

        assertThatCode(() -> service.completeProgress(999L)).doesNotThrowAnyException();
    }

    @Test
    void progressInfo_getPercentage_computesCorrectly() {
        ProgressTrackingService.ProgressInfo info = new ProgressTrackingService.ProgressInfo(200);
        info.processedItems = 50;

        assertThat(info.getPercentage()).isEqualTo(25.0);
    }

    @Test
    void progressInfo_getPercentage_withUnknownTotal_returnsZero() {
        ProgressTrackingService.ProgressInfo info = new ProgressTrackingService.ProgressInfo(-1);
        info.processedItems = 50;

        assertThat(info.getPercentage()).isEqualTo(0.0);
    }

    @Test
    void progressInfo_getEstimatedRemainingMs_withZeroProcessed_returnsZero() {
        ProgressTrackingService.ProgressInfo info = new ProgressTrackingService.ProgressInfo(100);

        assertThat(info.getEstimatedRemainingMs()).isZero();
    }
}
