package com.navio.communityservice.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.client.discovery.DiscoveryClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withResourceNotFound;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestSharedPlanDirectoryTests {

    private static final String TOKEN = "Abcdefghijklmnopqrstuvwxyz0123456789_-ABCDE";
    private static final String CARD_URL = "http://trips.test/v1/shared-plans/" + TOKEN + "/summary";

    private MockRestServiceServer server;
    private RestSharedPlanDirectory directory;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        var discovery = mock(DiscoveryClient.class);
        when(discovery.getInstances(anyString())).thenReturn(List.of());
        directory = new RestSharedPlanDirectory(discovery, URI.create("http://trips.test"), builder);
    }

    @Test
    void aPlanWithAnExploreCardIsListed() {
        server.expect(requestTo(CARD_URL)).andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"token\":\"" + TOKEN + "\"}", MediaType.APPLICATION_JSON));

        assertThat(directory.isListedOnExplore(TOKEN)).isTrue();
        server.verify();
    }

    @Test
    void profilePlansAreReadFromTheWrappedProfileShapeAcrossPagesWithTheAuthorsIdentity() {
        var author = java.util.UUID.fromString("00000000-0000-4000-8000-000000000001");
        String base = "http://trips.test/v1/shared-plans/by-author/" + author + "?size=48&page=";
        server.expect(requestTo(base + "0"))
                .andExpect(org.springframework.test.web.client.match.MockRestRequestMatchers.header("X-User-Id", author.toString()))
                .andRespond(withSuccess("{\"content\":[{\"plan\":{\"token\":\"tokenA\",\"title\":\"Kyoto\",\"dayCount\":3},\"pinned\":true}],\"last\":false}",
                        MediaType.APPLICATION_JSON));
        server.expect(requestTo(base + "1"))
                .andRespond(withSuccess("{\"content\":[{\"plan\":{\"token\":\"tokenB\",\"title\":\"Osaka\"},\"pinned\":false}],\"last\":true}",
                        MediaType.APPLICATION_JSON));

        assertThat(directory.listedByAuthor(author)).containsExactly(
                new SharedPlanDirectory.ListedPlan("tokenA", "Kyoto"), new SharedPlanDirectory.ListedPlan("tokenB", "Osaka"));
        server.verify();
    }

    @Test
    void aPlanWithoutAnExploreCardIsNotListed() {
        server.expect(requestTo(CARD_URL)).andRespond(withResourceNotFound());

        assertThat(directory.isListedOnExplore(TOKEN)).isFalse();
    }

    @Test
    void aFailingTripServiceIsReportedAsUnavailableRatherThanUnlisted() {
        server.expect(requestTo(CARD_URL)).andRespond(withStatus(HttpStatus.BAD_GATEWAY));

        assertThatThrownBy(() -> directory.isListedOnExplore(TOKEN))
                .isInstanceOf(SharedPlanDirectory.SharedPlanDirectoryUnavailableException.class);
    }
}
