package com.planelyx.keycloak;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jboss.logging.Logger;
import org.keycloak.util.JsonSerialization;

/**
 * Tells planelyx-api that a user now exists, so it can seed that user's default categories.
 *
 * Delivery is at-least-once, never exactly-once: a response can be lost after the API has already
 * committed, and this will retry anyway. The receiver is responsible for making a repeat harmless —
 * it seeds only when the owner has no categories at all.
 *
 * The API has no way to re-derive a missed registration, so the retries below are the whole of the
 * safety net. When they run out the user id is logged at ERROR, because that string is the only
 * thing needed to repair the account by hand.
 */
final class ProvisioningNotifier implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(ProvisioningNotifier.class);

    private static final String SIGNATURE_HEADER = "X-Planelyx-Signature";

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    /**
     * Roughly twenty seconds in total. Long enough to cover an API rolling restart, short enough
     * that a user who registers and lands on the dashboard is not waiting on the last attempt.
     */
    private static final long[] RETRY_DELAYS_MS = {1_000L, 4_000L, 15_000L};

    private final URI target;

    private final byte[] secret;

    private final HttpClient http;

    private final ScheduledExecutorService retries;

    ProvisioningNotifier(String target, String secret) {
        this.target = URI.create(target);
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        // Daemon threads: a pending retry must never be the reason Keycloak refuses to shut down.
        this.retries = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "planelyx-provisioning-retry");
            thread.setDaemon(true);
            return thread;
        });
    }

    void userRegistered(String userId, String realm) {
        byte[] body;

        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("userId", userId);
            payload.put("realm", realm);
            payload.put("timestamp", System.currentTimeMillis());
            body = JsonSerialization.writeValueAsBytes(payload);
        } catch (IOException e) {
            LOG.errorf(e, "Could not build the provisioning payload for user %s", userId);
            return;
        }

        send(userId, body, sign(body), 0);
    }

    private void send(String userId, byte[] body, String signature, int attempt) {
        HttpRequest request = HttpRequest.newBuilder(target)
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header(SIGNATURE_HEADER, signature)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        http.sendAsync(request, HttpResponse.BodyHandlers.discarding()).whenComplete((response, failure) -> {
            if (failure == null && response.statusCode() / 100 == 2) {
                return;
            }

            String cause = failure != null ? failure.toString() : "HTTP " + response.statusCode();
            retry(userId, body, signature, attempt, cause);
        });
    }

    private void retry(String userId, byte[] body, String signature, int attempt, String cause) {
        if (attempt >= RETRY_DELAYS_MS.length) {
            LOG.errorf(
                    "Gave up notifying planelyx-api that user %s registered (%s). That user has no default"
                            + " categories; re-send the event or seed them by hand.",
                    userId, cause);
            return;
        }

        long delay = RETRY_DELAYS_MS[attempt];
        LOG.warnf("Provisioning call for user %s failed (%s), retrying in %dms", userId, cause, delay);

        try {
            retries.schedule(() -> send(userId, body, signature, attempt + 1), delay, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            // Only reachable once the executor is shutting down, i.e. Keycloak is going away.
            LOG.errorf("Could not schedule a provisioning retry for user %s: %s", userId, e.toString());
        }
    }

    private String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            // HmacSHA256 is required of every JRE, so this only fires on an empty secret.
            throw new IllegalStateException("Could not sign the provisioning payload", e);
        }
    }

    @Override
    public void close() {
        retries.shutdownNow();
    }
}
