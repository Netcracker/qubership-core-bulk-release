package org.qubership.cloud.actions.renovate;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.stream.Collectors;

@Slf4j
public class HttpService {
    HttpClient httpClient;
    ObjectMapper mapper;

    public HttpService(HttpClient httpClient, ObjectMapper mapper) {
        this.httpClient = httpClient;
        this.mapper = mapper;
    }

    public HttpResponse<String> sendRequest(HttpRequest request, int retries, int... codes) throws IOException, InterruptedException {
        HttpResponse<String> response;
        Set<Integer> allowedResponses = Arrays.stream(codes).boxed().collect(Collectors.toSet());
        do {
            String method = request.method();
            URI uri = request.uri();
            if (log.isDebugEnabled()) {
                Optional<HttpRequest.BodyPublisher> bodyPublisher = request.bodyPublisher();
                String body = bodyPublisher.map(HttpService::getBodyAsString).orElse(null);
                String debug = String.format("Sending request: [%s] %s%s", method, uri, body == null ? "" : " body:\n" + body);
                log.debug(debug);
            }
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (log.isDebugEnabled()) {
                String body = mapper.writeValueAsString(mapper.readValue(response.body(), Object.class));
                String debug = String.format("Received response: [%s]%s", response.statusCode(), body == null ? "" : " body:\n" + body);
                log.debug(debug);
            }
            if (allowedResponses.contains(response.statusCode())) {
                return response;
            }
            // do not retry 4xx responses
            if (response.statusCode() >= 400 && response.statusCode() < 500) {
                break;
            }
            retries--;
            if (retries >= 0) Thread.sleep(3000);
        } while (retries >= 0);
        throw new IllegalStateException(String.format("Invalid response, status: %d, body: '%s'", response.statusCode(), response.body()));
    }

    public static String getBodyAsString(HttpRequest.BodyPublisher publisher) {
        CompletableFuture<String> future = new CompletableFuture<>();
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        publisher.subscribe(new Flow.Subscriber<>() {

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                subscription.request(Long.MAX_VALUE);
            }

            @Override
            public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()];
                item.get(bytes);
                output.write(bytes, 0, bytes.length);
            }

            @Override
            public void onError(Throwable throwable) {
                future.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                future.complete(output.toString(StandardCharsets.UTF_8));
            }
        });

        try {
            return future.get();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read content of the HttpRequest.BodyPublisher", e);
        }
    }

}
