package com.cinemaabyss.events;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kafka integration tests for EventsController.
 *
 * Поднимается реальный in-process Kafka брокер через @EmbeddedKafka.
 * Каждый тест:
 *   1. Отправляет HTTP POST через MockMvc (тот же путь, что и у реального клиента)
 *   2. Читает сообщение из топика через KafkaTestUtils
 *   3. Проверяет, что payload содержит ожидаемые поля
 *
 * Внешних зависимостей не нужно — Kafka запускается внутри JVM.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EmbeddedKafka(
        partitions = 1,
        topics = {"movie-events", "user-events", "payment-events"},
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class EventsKafkaIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EmbeddedKafkaBroker embeddedKafka;

    /** Создаёт консьюмер с StringDeserializer для ключа и значения. */
    private Consumer<String, String> consumer(String groupId) {
        Map<String, Object> props = KafkaTestUtils.consumerProps(groupId, "true", embeddedKafka);
        return new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer())
                .createConsumer();
    }

    /**
     * Возвращает все ключи из батча записей.
     * Нужно т.к. тесты делят один embedded-брокер: в топике могут быть записи
     * из предыдущих тестов, и нас интересует ЛЮБАЯ запись с нужным ключом.
     */
    private List<String> keys(ConsumerRecords<String, String> records) {
        List<String> result = new ArrayList<>();
        for (ConsumerRecord<String, String> r : records) result.add(r.key());
        return result;
    }

    // ── Movie events ──────────────────────────────────────────────────────────

    @Test
    void movieEvent_messageArrivesInKafka_withGeneratedFields() throws Exception {
        mockMvc.perform(post("/api/events/movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"movie_id": 10, "title": "Dune", "action": "viewed", "user_id": 1}
                                """))
                .andExpect(status().isCreated());

        Map<String, Object> props = KafkaTestUtils.consumerProps("it-movie", "true", embeddedKafka);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "movie-events");
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            String payload = records.iterator().next().value();

            // Данные из запроса
            assertThat(payload).contains("\"movie_id\":10");
            assertThat(payload).contains("\"title\":\"Dune\"");
            assertThat(payload).contains("\"action\":\"viewed\"");
            // Поля, генерируемые фабричным методом MovieEvent.from()
            assertThat(payload).contains("event_id");
            assertThat(payload).contains("published_at");
        }
    }

    @Test
    void movieEvent_kafkaKey_containsMovieId() throws Exception {
        mockMvc.perform(post("/api/events/movie")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"movie_id": 42, "title": "Interstellar", "action": "rated", "user_id": 7}
                                """))
                .andExpect(status().isCreated());

        Map<String, Object> props = KafkaTestUtils.consumerProps("it-movie-key", "true", embeddedKafka);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "movie-events");
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            // Ключ сообщения должен быть "movie-<movieId>" — см. EventsController
            // Проверяем среди всех записей: брокер общий, в топике могут быть записи из др. тестов
            assertThat(keys(records)).contains("movie-42");
        }
    }

    // ── User events ───────────────────────────────────────────────────────────

    @Test
    void userEvent_messageArrivesInKafka_withGeneratedFields() throws Exception {
        mockMvc.perform(post("/api/events/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": 5, "action": "registered", "username": "kafka_test_user"}
                                """))
                .andExpect(status().isCreated());

        Map<String, Object> props = KafkaTestUtils.consumerProps("it-user", "true", embeddedKafka);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "user-events");
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            String payload = records.iterator().next().value();

            assertThat(payload).contains("\"user_id\":5");
            assertThat(payload).contains("kafka_test_user");
            assertThat(payload).contains("event_id");
            assertThat(payload).contains("published_at");
        }
    }

    @Test
    void userEvent_kafkaKey_containsUserId() throws Exception {
        mockMvc.perform(post("/api/events/user")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"user_id": 99, "action": "login", "username": "bob"}
                                """))
                .andExpect(status().isCreated());

        Map<String, Object> props = KafkaTestUtils.consumerProps("it-user-key", "true", embeddedKafka);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "user-events");
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            assertThat(keys(records)).contains("user-99");
        }
    }

    // ── Payment events ────────────────────────────────────────────────────────

    @Test
    void paymentEvent_messageArrivesInKafka_withGeneratedFields() throws Exception {
        mockMvc.perform(post("/api/events/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payment_id": 200, "user_id": 5, "amount": 9.99, "status": "completed"}
                                """))
                .andExpect(status().isCreated());

        Map<String, Object> props = KafkaTestUtils.consumerProps("it-payment", "true", embeddedKafka);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "payment-events");
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            String payload = records.iterator().next().value();

            assertThat(payload).contains("\"payment_id\":200");
            assertThat(payload).contains("9.99");
            assertThat(payload).contains("\"status\":\"completed\"");
            assertThat(payload).contains("event_id");
            assertThat(payload).contains("published_at");
        }
    }

    @Test
    void paymentEvent_kafkaKey_containsPaymentId() throws Exception {
        mockMvc.perform(post("/api/events/payment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"payment_id": 777, "user_id": 1, "amount": 49.99, "status": "pending"}
                                """))
                .andExpect(status().isCreated());

        Map<String, Object> props = KafkaTestUtils.consumerProps("it-payment-key", "true", embeddedKafka);
        try (Consumer<String, String> consumer = new DefaultKafkaConsumerFactory<>(props, new StringDeserializer(), new StringDeserializer()).createConsumer()) {
            embeddedKafka.consumeFromAnEmbeddedTopic(consumer, "payment-events");
            ConsumerRecords<String, String> records = KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

            assertThat(records.count()).isGreaterThanOrEqualTo(1);
            assertThat(keys(records)).contains("payment-777");
        }
    }
}
