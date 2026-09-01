package com.eazy.batch.websocket;

import com.eazy.batch.autoconfigure.BatchProcessorProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

/**
 * Registers a STOMP-over-WebSocket endpoint for live batch progress and
 * completion push. No external broker (Kafka/RabbitMQ/etc.) involved -
 * this uses Spring's built-in in-memory simple broker, which is enough for
 * broadcasting job progress to whichever browser tabs are subscribed.
 *
 * <p>Client connects to {@code ws://host:port{websocketEndpoint}} (SockJS
 * fallback included at the same path) and subscribes to
 * {@code {websocketTopicPrefix}/{jobExecutionId}} to receive that job's
 * {@link com.eazy.batch.dto.BatchProgressMessage}s.</p>
 *
 * <p>Only activated when {@code eazy.batch.websocket-enabled=true} (default)
 * - imported unconditionally by
 * {@link com.eazy.batch.autoconfigure.BatchProcessorAutoConfiguration} but
 * these class-level conditions still gate whether it actually activates.</p>
 */
@Slf4j
@RequiredArgsConstructor
@ConditionalOnClass(EnableWebSocketMessageBroker.class)
@ConditionalOnProperty(prefix = "eazy.batch", name = "websocket-enabled", havingValue = "true", matchIfMissing = true)
@EnableWebSocketMessageBroker
public class BatchWebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final BatchProcessorProperties properties;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        registry.addEndpoint(properties.getWebsocketEndpoint())
                .setAllowedOriginPatterns("*")
                .withSockJS();
        log.info("✅ Batch WebSocket STOMP endpoint registered at {}", properties.getWebsocketEndpoint());
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        // Simple in-memory broker - fine for progress broadcast, no external
        // message infrastructure (Kafka/RabbitMQ) required.
        registry.enableSimpleBroker(topLevelSegmentOf(properties.getWebsocketTopicPrefix()));
        registry.setApplicationDestinationPrefixes("/app");
    }

    /**
     * enableSimpleBroker expects a destination prefix like "/topic", not the
     * full "/topic/batch-progress" - extract just the first segment so a
     * custom websocketTopicPrefix still registers a valid broker prefix.
     */
    private String topLevelSegmentOf(String prefix) {
        if (prefix == null || prefix.isBlank()) return "/topic";
        String trimmed = prefix.startsWith("/") ? prefix.substring(1) : prefix;
        int slash = trimmed.indexOf('/');
        String segment = slash >= 0 ? trimmed.substring(0, slash) : trimmed;
        return "/" + segment;
    }
}
