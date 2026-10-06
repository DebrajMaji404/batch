package com.eazy.batch.service;

import com.eazy.batch.dto.BatchProgressMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;

/**
 * Pushes {@link BatchProgressMessage}s to {@code {topicPrefix}/{jobExecutionId}}
 * over STOMP/WebSocket. No Kafka, no external broker - Spring's built-in
 * in-memory STOMP broker (registered by {@link com.eazy.batch.websocket.BatchWebSocketConfig})
 * handles delivery to whichever clients are subscribed to that job's topic.
 *
 * Registered exclusively via BatchProcessorAutoConfiguration#batchWebSocketNotifier -
 * intentionally NOT annotated with @Service; see MetricsService for why.
 */
@Slf4j
public class BatchWebSocketNotifier {

    private final SimpMessagingTemplate messagingTemplate;
    private final boolean enabled;
    private final String topicPrefix;
    /** Destination for per-user pushes (e.g. /queue/batch-progress); null/blank disables them. */
    private final String userQueue;

    public BatchWebSocketNotifier(SimpMessagingTemplate messagingTemplate, boolean enabled, String topicPrefix) {
        this(messagingTemplate, enabled, topicPrefix, null);
    }

    public BatchWebSocketNotifier(SimpMessagingTemplate messagingTemplate, boolean enabled, String topicPrefix,
                                  String userQueue) {
        this.messagingTemplate = messagingTemplate;
        this.enabled = enabled;
        this.topicPrefix = topicPrefix;
        this.userQueue = userQueue;
    }

    private boolean isActive() {
        return enabled && messagingTemplate != null;
    }

    public void send(Long jobExecutionId, BatchProgressMessage message) {
        send(jobExecutionId, null, message);
    }

    /**
     * Sends to the per-job topic and, when {@code username} is known and a user queue is
     * configured, also to that user's private queue ({@code /user/queue/batch-progress}),
     * so a client can follow all of its own uploads with one subscription.
     * The message is always recorded in {@link BatchRunRegistry} first, so the status
     * endpoint works even with WebSocket disabled.
     */
    public void send(Long jobExecutionId, String username, BatchProgressMessage message) {
        if (jobExecutionId == null) return;
        BatchRunRegistry.record(message);
        if (!isActive()) return;
        try {
            String destination = topicPrefix + "/" + jobExecutionId;
            messagingTemplate.convertAndSend(destination, message);
            if (username != null && !username.isBlank() && userQueue != null && !userQueue.isBlank()) {
                messagingTemplate.convertAndSendToUser(username, userQueue, message);
            }
            log.debug("Sent {} WebSocket message to {}", message.getType(), destination);
        } catch (Exception e) {
            // Never let a broken WebSocket session fail the batch job itself.
            log.warn("Failed to send WebSocket progress message for job execution {}: {}",
                    jobExecutionId, e.getMessage());
        }
    }
}
