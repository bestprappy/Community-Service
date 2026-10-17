package com.navio.communityservice.integration;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.client.ServiceInstance;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;

/**
 * Asks trip planning for a plan's Explore card. The card endpoint answers
 * {@code 404} for anything not listed right now, which is exactly the rule a post
 * attachment needs; looking a card up does not count as a view of the plan.
 */
@Component
public class RestSharedPlanDirectory implements SharedPlanDirectory {

    private static final String TRIP_PLANNING_SERVICE_ID = "trip-planning-service";

    private final RestClient.Builder restClientBuilder;
    private final DiscoveryClient discoveryClient;
    private final URI fallbackBaseUrl;

    @Autowired
    public RestSharedPlanDirectory(
            RestClient.Builder restClientBuilder,
            DiscoveryClient discoveryClient,
            @Value("${navio.trip-planning.base-url:http://localhost:8082}") URI fallbackBaseUrl) {
        this(discoveryClient, fallbackBaseUrl, restClientBuilder.clone().requestFactory(shortTimeouts()));
    }

    /** For tests: a builder already bound to a mock server. */
    RestSharedPlanDirectory(DiscoveryClient discoveryClient, URI fallbackBaseUrl, RestClient.Builder restClientBuilder) {
        this.restClientBuilder = restClientBuilder;
        this.discoveryClient = discoveryClient;
        this.fallbackBaseUrl = fallbackBaseUrl;
    }

    /** Short: the caller is a person waiting for their post to publish. */
    private static JdkClientHttpRequestFactory shortTimeouts() {
        var factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        return factory;
    }

    @Override
    public boolean isListedOnExplore(String token) {
        URI baseUrl = discoveryClient.getInstances(TRIP_PLANNING_SERVICE_ID).stream()
                .findFirst().map(ServiceInstance::getUri).orElse(fallbackBaseUrl);
        try {
            return restClientBuilder.clone().baseUrl(baseUrl.toString()).build()
                    .get().uri("/v1/shared-plans/{token}/summary", token)
                    .exchange((request, response) -> {
                        if (response.getStatusCode().is2xxSuccessful()) return true;
                        if (response.getStatusCode().isSameCodeAs(HttpStatus.NOT_FOUND)) return false;
                        throw new SharedPlanDirectoryUnavailableException(
                                "Trip planning answered " + response.getStatusCode().value(), null);
                    });
        } catch (RestClientException exception) {
            throw new SharedPlanDirectoryUnavailableException("Trip planning is unreachable", exception);
        }
    }
}
