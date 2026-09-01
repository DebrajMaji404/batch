package com.eazy.batch.listener;

import com.eazy.batch.dto.BatchProgressMessage;
import com.eazy.batch.service.BatchWebSocketNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.listener.ChunkListener;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.lang.NonNull;

/**
 * Broadcasts a {@link BatchProgressMessage} (type PROGRESS) over WebSocket
 * after every chunk. One shared instance handles every job - it reads
 * jobExecutionId/jobName/counts straight off the StepExecution passed to
 * {@code afterChunk}, so no per-job generated code is needed (unlike
 * SkipListener, which needs your DTO/WRAPPER generic types).
 *
 * Registered exclusively via BatchProcessorAutoConfiguration and attached to
 * every generated Step (both {@code @BatchJob} and {@code @BatchExportJob}) -
 * intentionally NOT annotated with @Component; see MetricsService for why.
 */
@Slf4j
@RequiredArgsConstructor
public class BatchProgressChunkListener implements ChunkListener {

    private final BatchWebSocketNotifier webSocketNotifier;

    @Override
    public void afterChunk(@NonNull ChunkContext context) {
        StepExecution stepExecution = context.getStepContext().getStepExecution();
        Long jobExecutionId = stepExecution.getJobExecutionId();
        String jobName = stepExecution.getJobExecution().getJobInstance().getJobName();

        BatchProgressMessage message = BatchProgressMessage.builder()
                .type(BatchProgressMessage.Type.PROGRESS)
                .jobExecutionId(jobExecutionId)
                .jobName(jobName)
                .readCount(stepExecution.getReadCount())
                .writeCount(stepExecution.getWriteCount())
                .skipCount(stepExecution.getSkipCount())
                .build();

        webSocketNotifier.send(jobExecutionId, message);
    }
}
