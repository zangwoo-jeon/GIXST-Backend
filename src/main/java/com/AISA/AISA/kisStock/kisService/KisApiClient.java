package com.AISA.AISA.kisStock.kisService;

import com.AISA.AISA.kisStock.kisService.Auth.KisAuthService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import org.springframework.lang.NonNull;
import java.util.function.Function;

@Component
@RequiredArgsConstructor
@Slf4j
public class KisApiClient {

    private final KisAuthService kisAuthService;

    /**
     * Executes a KIS API call with automatic retry on 401/403 errors.
     *
     * @param requestBuilder A function that takes an access token and returns a
     *                       WebClient RequestHeadersSpec.
     * @param responseType   The class type of the response.
     * @param <T>            The type of the response.
     * @return The response object.
     */
    public <T> T fetch(Function<String, WebClient.RequestHeadersSpec<?>> requestBuilder,
            @NonNull Class<T> responseType) {
        String token = kisAuthService.getAccessToken();

        try {
            return requestBuilder.apply(token)
                    .retrieve()
                    .bodyToMono(responseType)
                    .block();
        } catch (WebClientResponseException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED || e.getStatusCode() == HttpStatus.FORBIDDEN) {
                log.warn("KIS API Unauthorized/Forbidden ({}). Invalidating token and retrying...", e.getStatusCode());
                kisAuthService.invalidateToken();
                String newToken = kisAuthService.getAccessToken();
                return requestBuilder.apply(newToken)
                        .retrieve()
                        .bodyToMono(responseType)
                        .block();
            }
            throw e;
        } catch (Exception e) {
            log.error("Unhandled exception during KIS API call: {}", e.getMessage());
            throw e;
        }
    }
}
