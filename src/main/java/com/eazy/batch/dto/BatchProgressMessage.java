package com.eazy.batch.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Message broadcast over WebSocket (STOMP) to
 * {@code {websocketTopicPrefix}/{jobExecutionId}}, e.g. {@code /topic/batch-progress/42}.
 *
 * <p>{@code type = PROGRESS} messages are sent after every chunk while the job runs.
 * Exactly one {@code type = COMPLETED} or {@code type = FAILED} message is sent at the
 * end. If any rows were skipped, that final message carries a base64-encoded Excel
 * file (3 columns: item / phase / reason) in {@code errorFileBase64} — decode and save
 * it client-side, or turn it directly into a download link.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BatchProgressMessage {

    public enum Type { PROGRESS, COMPLETED, FAILED }

    private Type type;
    private Long jobExecutionId;
    private String jobName;

    /** Rows read so far (this step). */
    private long readCount;
    /** Rows written so far (this step). */
    private long writeCount;
    /** Rows skipped so far (this step). */
    private long skipCount;

    /** Only set on COMPLETED/FAILED. */
    private Long durationMs;

    /** Only set on COMPLETED/FAILED, and only if skipCount > 0. */
    private String errorFileName;
    /** Base64-encoded .xlsx bytes. Only set alongside errorFileName. */
    private String errorFileBase64;
    private Integer errorFileSizeBytes;
}
