package com.cinemaabyss.events.kafka;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

@Service
public class EventProducer {

    private static final Logger log = LoggerFactory.getLogger(EventProducer.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public EventProducer(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(String topic, String key, Object payload) throws JsonProcessingException {
        String json = objectMapper.writeValueAsString(payload);
        CompletableFuture<SendResult<String, String>> future = kafkaTemplate.send(topic, key, json);
        future.whenComplete((result, ex) -> {
            if (ex != null) {
                log.error("[kafka] failed to publish to {}: {}", topic, ex.getMessage());
            } else {
                log.info("[kafka] published to {} partition={} offset={} value={}",
                        topic,
                        result.getRecordMetadata().partition(),
                        result.getRecordMetadata().offset(),
                        json);
            }
        });
    }
}
