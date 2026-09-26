package com.assignment.notificationservice.integration;

import com.assignment.notificationservice.BaseIntegrationTest;
import com.assignment.notificationservice.dtos.SendNotificationRequest;
import com.assignment.notificationservice.dtos.SendNotificationResponse;
import com.assignment.notificationservice.models.enums.Channel;
import com.assignment.notificationservice.models.enums.NotificationStatus;
import com.assignment.notificationservice.support.TestSender;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Idempotent submit: one logical message → one row, no matter how many times or how
 * concurrently it is sent.
 */
class IngestionIdempotencyTest extends BaseIntegrationTest {

    private static final int THREADS = 20;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private TestSender sender;

    @BeforeEach
    void setUp() {
        sender = setupSender("idem");
    }

    /**
     * The most important test in the repo. 20 threads are parked on a gate and released at
     * once with the same key and payload. Exactly one INSERT may win; every loser must
     * converge on the winner's row and answer 200 — never 409 or 500.
     */
    @Test
    void concurrent_submit_same_key_creates_exactly_one() throws Exception {
        String key = newIdempotencyKey();
        SendNotificationRequest request = new SendNotificationRequest(
                Channel.EMAIL, "test@example.com", WELCOME_TEMPLATE,
                Map.of("name", "Alice", "companyName", "Acme"), null);

        List<ResponseEntity<SendNotificationResponse>> responses =
                submitConcurrently(THREADS, i -> request, key, SendNotificationResponse.class);

        assertThat(responses).hasSize(THREADS);
        assertThat(responses).extracting(ResponseEntity::getStatusCode)
                .allMatch(s -> s == HttpStatus.ACCEPTED || s == HttpStatus.OK);
        assertThat(responses.stream().filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED).count())
                .as("exactly one request creates the row").isEqualTo(1);

        Set<UUID> ids = responses.stream().map(r -> r.getBody().id()).collect(Collectors.toSet());
        assertThat(ids).as("every caller sees the same notification").hasSize(1);
        UUID id = ids.iterator().next();

        assertThat(countRowsForKey(key)).as("rows in the database").isEqualTo(1);
        assertThat(countEvents(id)).as("exactly one creation audit event").isEqualTo(1);
        assertThat(responses).allSatisfy(r -> assertThat(r.getBody().status()).isEqualTo(NotificationStatus.PENDING));
    }

    /**
     * Harder race: the 20 threads send two different payloads under one key. Whichever
     * payload wins the insert, its senders get 202/200 with the winning ID; senders of the
     * other payload get 422. Still exactly one row.
     */
    @Test
    void concurrent_submit_same_key_different_payloads_one_wins_rest_get_422() throws Exception {
        String key = newIdempotencyKey();

        List<ResponseEntity<JsonNode>> responses = submitConcurrently(THREADS,
                i -> new SendNotificationRequest(Channel.EMAIL,
                        i % 2 == 0 ? "even@example.com" : "odd@example.com", WELCOME_TEMPLATE,
                        Map.of("name", "Alice", "companyName", "Acme"), null),
                key, JsonNode.class);

        assertThat(countRowsForKey(key)).isEqualTo(1);
        String winningRecipient = jdbcTemplate.queryForObject(
                "SELECT recipient FROM notification WHERE tenant_id = ? AND idempotency_key = ?",
                String.class, sender.tenant().id(), key);

        List<ResponseEntity<JsonNode>> accepted = responses.stream()
                .filter(r -> r.getStatusCode().is2xxSuccessful()).toList();
        List<ResponseEntity<JsonNode>> rejected = responses.stream()
                .filter(r -> r.getStatusCode() == HttpStatus.UNPROCESSABLE_ENTITY).toList();

        assertThat(accepted.size() + rejected.size()).as("no 409/500 leaked out of the race").isEqualTo(THREADS);
        assertThat(accepted).hasSize(THREADS / 2).allSatisfy(r ->
                assertThat(r.getBody().get("recipient").asText()).isEqualTo(winningRecipient));
        assertThat(accepted.stream().filter(r -> r.getStatusCode() == HttpStatus.ACCEPTED).count()).isEqualTo(1);
        assertThat(rejected).hasSize(THREADS / 2).allSatisfy(r ->
                assertThat(r.getBody().get("title").asText()).isEqualTo("Idempotency Key Conflict"));
    }

    @Test
    void same_key_different_payload_returns422() {
        String key = newIdempotencyKey();
        send(sender, key, welcomeEmail("test@example.com"));

        ResponseEntity<JsonNode> second = send(sender, key, welcomeEmail("other@example.com"));

        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(second.getBody().get("detail").asText()).contains(key);
        assertThat(countRowsForKey(key)).isEqualTo(1);
    }

    @Test
    void same_key_same_payload_returns200_with_original() {
        String key = newIdempotencyKey();

        ResponseEntity<JsonNode> first = send(sender, key, welcomeEmail("test@example.com"));
        ResponseEntity<JsonNode> second = send(sender, key, welcomeEmail("test@example.com"));

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        assertThat(countRowsForKey(key)).isEqualTo(1);
    }

    @Test
    void replay_is_recognised_regardless_of_variable_order() {
        String key = newIdempotencyKey();
        String first = "{\"channel\":\"EMAIL\",\"recipient\":\"a@example.com\",\"templateCode\":\"welcome\","
                + "\"variables\":{\"name\":\"Alice\",\"companyName\":\"Acme\"}}";
        String reordered = "{\"templateCode\":\"welcome\",\"recipient\":\"a@example.com\",\"channel\":\"EMAIL\","
                + "\"variables\":{\"companyName\":\"Acme\",\"name\":\"Alice\"}}";

        assertThat(sendRaw(key, first)).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(sendRaw(key, reordered)).isEqualTo(HttpStatus.OK);
    }

    @Test
    void replay_after_cancel_returns_the_cancelled_notification_not_a_new_one() {
        String key = newIdempotencyKey();
        String id = send(sender, key, welcomeEmail("test@example.com")).getBody().get("id").asText();
        restTemplate.exchange("/api/v1/notifications/" + id + "/cancel", HttpMethod.POST,
                new HttpEntity<>(apiKeyHeaders(sender.apiKey(), null)), JsonNode.class);

        ResponseEntity<JsonNode> replay = send(sender, key, welcomeEmail("test@example.com"));

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(replay.getBody().get("id").asText()).isEqualTo(id);
        assertThat(replay.getBody().get("status").asText()).isEqualTo("CANCELLED");
    }

    @Test
    void same_key_in_different_tenants_are_independent() {
        TestSender other = setupSender("idem-other");
        String key = newIdempotencyKey();

        ResponseEntity<JsonNode> mine = send(sender, key, welcomeEmail("test@example.com"));
        ResponseEntity<JsonNode> theirs = send(other, key, welcomeEmail("someone-else@example.com"));

        assertThat(mine.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(theirs.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(mine.getBody().get("id")).isNotEqualTo(theirs.getBody().get("id"));
    }

    // ---- helpers ----

    /**
     * Runs {@code threads} submits with one idempotency key. Every worker first checks in on
     * {@code ready}, then blocks on {@code go}, so all requests leave at the same instant.
     */
    private <T> List<ResponseEntity<T>> submitConcurrently(int threads,
                                                           java.util.function.IntFunction<SendNotificationRequest> payload,
                                                           String key, Class<T> responseType) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<ResponseEntity<T>>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                SendNotificationRequest request = payload.apply(i);
                Callable<ResponseEntity<T>> call = () -> {
                    ready.countDown();
                    go.await();
                    return restTemplate.exchange("/api/v1/notifications", HttpMethod.POST,
                            new HttpEntity<>(request, apiKeyHeaders(sender.apiKey(), key)), responseType);
                };
                futures.add(pool.submit(call));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).as("all workers ready").isTrue();
            go.countDown();

            List<ResponseEntity<T>> responses = new ArrayList<>();
            for (Future<ResponseEntity<T>> f : futures) {
                responses.add(f.get(30, TimeUnit.SECONDS));   // rethrows any worker exception
            }
            return responses;
        } finally {
            pool.shutdownNow();
        }
    }

    private HttpStatusCode sendRaw(String key, String json) {
        return restTemplate.exchange("/api/v1/notifications", HttpMethod.POST,
                new HttpEntity<>(json, apiKeyHeaders(sender.apiKey(), key)), String.class).getStatusCode();
    }

    private int countRowsForKey(String key) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification WHERE tenant_id = ? AND idempotency_key = ?",
                Integer.class, sender.tenant().id(), key);
    }

    private int countEvents(UUID notificationId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_event WHERE notification_id = ?", Integer.class, notificationId);
    }
}
