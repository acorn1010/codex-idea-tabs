package com.acorn.codextabs.core;

import com.google.gson.JsonParser;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.Flow;

/** Check API metadata with a time and size limit. Never follow redirects or send a model prompt. */
public final class ApiProbe {
    private static final int MAX_BYTES = 1_048_576;
    private ApiProbe() {}
    /** Keep the outcome separate from its message so callers can show a reliable status icon. */
    public record Result(boolean success, String message) {}
    public static Result check(ApiConnection entry, String secret) {
        entry.validate();
        if (entry.hasKey && secret.isBlank()) { return new Result(false, "Saved key is unavailable. Enter it again."); }
        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build()) {
            var request = HttpRequest.newBuilder(URI.create(entry.baseUrl + "/models")).timeout(Duration.ofSeconds(15)).header("Accept", "application/json");
            if (!secret.isBlank()) { request.header("Authorization", "Bearer " + secret); }
            var pending = client.sendAsync(request.GET().build(), info -> new LimitedBody());
            HttpResponse<byte[]> response;
            try { response = pending.get(15, TimeUnit.SECONDS); }
            catch (Exception error) { pending.cancel(true); throw error; }
            if (response.statusCode() == 401 || response.statusCode() == 403) { return new Result(false, "API rejected the key. Check its value and access."); }
            if (response.statusCode() == 404 || response.statusCode() == 405) { return new Result(false, "This API does not list models. Save it and try a chat."); }
            if (response.statusCode() != 200) { return new Result(false, "API returned HTTP " + response.statusCode() + ". Check the base URL."); }
            var data = JsonParser.parseString(new String(response.body(), StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("data");
            var available = new HashSet<String>();
            if (data != null) { data.forEach(value -> available.add(value.getAsJsonObject().get("id").getAsString())); }
            boolean success = available.containsAll(entry.models);
            return new Result(success, success ? "API reachable. Models found. Chat generation has not been tested." : "API reachable. Some model IDs were not listed. Check their spelling.");
        } catch (Exception error) {
            if (error instanceof InterruptedException) { Thread.currentThread().interrupt(); }
            return new Result(false, "Could not check the API. Check the URL and your network connection.");
        }
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private Flow.Subscription subscription;
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription value) { subscription = value; value.request(1); }
        @Override public void onNext(List<ByteBuffer> buffers) {
            for (var buffer : buffers) {
                if (buffer.remaining() > MAX_BYTES - bytes.size()) { subscription.cancel(); result.completeExceptionally(new IllegalStateException("API model list exceeds the size limit")); return; }
                byte[] data = new byte[buffer.remaining()]; buffer.get(data); bytes.writeBytes(data);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(bytes.toByteArray()); }
    }
}
