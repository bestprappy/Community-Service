package com.navio.communityservice.service;

import tools.jackson.databind.ObjectMapper;
import com.navio.communityservice.dto.NotificationEventDtos.QuarantinedEventResponse;
import com.navio.communityservice.dto.NotificationEventDtos.ReplayResponse;
import com.navio.communityservice.exception.GroupException;
import com.navio.communityservice.integration.SharedPlanDirectory;
import com.navio.communityservice.integration.tripevents.TripEvent;
import com.navio.communityservice.repository.QuarantinedEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * The operator's view of quarantined integration events, and their replay.
 *
 * <p>A replay runs the stored record through the same processor as live
 * consumption, so the processed-event record makes it safe to replay an event
 * that was in fact delivered later, or to replay twice.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class QuarantinedEventService {

    private final QuarantinedEventRepository quarantine;
    private final TripEventProcessor processor;
    private final ObjectMapper json;

    public Page<QuarantinedEventResponse> list(boolean openOnly, int page, int size) {
        return quarantine.list(openOnly, PostService.pagination(page, size));
    }

    public ReplayResponse replay(UUID id, UUID operator) {
        QuarantinedEventResponse stored = quarantine.find(id)
                .orElseThrow(() -> new GroupException(HttpStatus.NOT_FOUND, "Quarantined event not found"));
        TripEventProcessor.Outcome outcome;
        try {
            outcome = processor.process(TripEvent.parse(stored.payload(), json));
        } catch (TripEvent.MalformedTripEventException malformed) {
            throw new GroupException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "This event is still malformed and cannot be replayed: " + malformed.getMessage());
        } catch (SharedPlanDirectory.SharedPlanDirectoryUnavailableException unavailable) {
            throw new GroupException(HttpStatus.SERVICE_UNAVAILABLE, "Trip planning is unavailable; try the replay again shortly");
        }
        quarantine.markReplayed(id, outcome.name());
        log.info("Operator {} replayed quarantined event {} ({}): {}", operator, id, stored.eventId(), outcome);
        return new ReplayResponse(id, outcome.name());
    }
}
