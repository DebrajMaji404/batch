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

    public BatchWebSocketNotifier(SimpMessagingTemplate messagingTemplate, boolean enabled, String topicPrefix) {
        this.messagingTemplate = messagingTemplate;
        this.enabled = enabled;
        this.topicPrefix = topicPrefix;
    }

    private boolean isActive() {
        return enabled && messagingTemplate != null;
    }

    public void send(Long jobExecutionId, BatchProgressMessage message) {
        if (!isActive() || jobExecutionId == null) return;
        try {
            String destination = topicPrefix + "/" + jobExecutionId;
            messagingTemplate.convertAndSend(destination, message);
            log.debug("Sent {} WebSocket message to {}", message.getType(), destination);
        } catch (Exception e) {
            // Never let a broken WebSocket session fail the batch job itself.
            log.warn("Failed to send WebSocket progress message for job execution {}: {}",
                    jobExecutionId, e.getMessage());
        }
    }
}
