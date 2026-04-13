package com.cinemaabyss.events.kafka;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
public class EventConsumer {

    private static final Logger log = LoggerFactory.getLogger(EventConsumer.class);

    @KafkaListener(topics = "movie-events", groupId = "events-service-consumer")
    public void consumeMovieEvent(ConsumerRecord<String, String> record) {
        log.info("[consumer] movie-events | partition={} offset={} key={} value={}",
                record.partition(), record.offset(), record.key(), record.value());
    }

    @KafkaListener(topics = "user-events", groupId = "events-service-consumer")
    public void consumeUserEvent(ConsumerRecord<String, String> record) {
        log.info("[consumer] user-events | partition={} offset={} key={} value={}",
                record.partition(), record.offset(), record.key(), record.value());
    }

    @KafkaListener(topics = "payment-events", groupId = "events-service-consumer")
    public void consumePaymentEvent(ConsumerRecord<String, String> record) {
        log.info("[consumer] payment-events | partition={} offset={} key={} value={}",
                record.partition(), record.offset(), record.key(), record.value());
    }
}
