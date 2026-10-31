package com.navio.communityservice.service;

import com.navio.communityservice.repository.QuarantinedEventRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Live-delivery and consumer health for Prometheus. */
@Component
public class NotificationDeliveryMetrics {

    public NotificationDeliveryMetrics(ObjectProvider<MeterRegistry> registries, NotificationStreamHub hub,
                                       QuarantinedEventRepository quarantine) {
        MeterRegistry registry = registries.getIfAvailable();
        if (registry == null) return;
        Gauge.builder("navio.notifications.streams.active", hub, NotificationStreamHub::activeStreams)
                .description("Open live notification streams on this instance")
                .register(registry);
        Gauge.builder("navio.notifications.events.quarantined", quarantine, QuarantinedEventRepository::openCount)
                .description("Integration events awaiting operator replay")
                .register(registry);
    }
}
