package com.navio.communityservice.service;

import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

/**
 * Announces that recipients' inboxes changed, for live delivery.
 *
 * <p>Call it inside the transaction that changed the inbox. The live stream only
 * acts after that transaction commits, so a rolled-back change never reaches a
 * browser, and the signal carries no notification content: browsers re-read
 * their inbox through the authorised API.
 */
@Component
@RequiredArgsConstructor
public class InboxSignals {

    public enum Kind {
        /** New notifications arrived. */
        CREATED,
        /** Something was marked read; other tabs and devices should refresh the count. */
        READ_STATE,
        /** Notifications were removed, for example by a block. */
        REMOVED
    }

    public record InboxChanged(Set<UUID> recipients, Kind kind) {
        public InboxChanged {
            recipients = Set.copyOf(recipients);
        }
    }

    private final ApplicationEventPublisher events;

    public void created(Collection<UUID> recipients) {
        publish(recipients, Kind.CREATED);
    }

    public void readStateChanged(UUID recipient) {
        publish(Set.of(recipient), Kind.READ_STATE);
    }

    public void removed(Collection<UUID> recipients) {
        publish(recipients, Kind.REMOVED);
    }

    private void publish(Collection<UUID> recipients, Kind kind) {
        if (recipients == null || recipients.isEmpty()) return;
        events.publishEvent(new InboxChanged(Set.copyOf(recipients), kind));
    }
}
