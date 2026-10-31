-- Consumption of trip.events.v1 for follower notifications.
--
-- Additive only: the previous image never reads these tables, so a rollback keeps
-- working with them in place.

-- One row per (consumer, event id) the service finished handling. Written in the
-- same transaction as the notifications the event created, so a redelivered or
-- replayed event is recognised and creates nothing a second time.
CREATE TABLE IF NOT EXISTS notif.processed_events (
    consumer     VARCHAR(80)  NOT NULL,
    event_id     UUID         NOT NULL,
    event_type   VARCHAR(100) NOT NULL,
    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    outcome      VARCHAR(30)  NOT NULL,
    CONSTRAINT pk_notif_processed_events PRIMARY KEY (consumer, event_id)
);
CREATE INDEX IF NOT EXISTS idx_notif_processed_events_time ON notif.processed_events (processed_at);

-- Events that could not be handled after retries (malformed, unsupported shape,
-- or a dependency down for longer than the retry window). Kept with diagnostics
-- for an operator to inspect and replay; never silently dropped.
CREATE TABLE IF NOT EXISTS notif.quarantined_events (
    id              UUID         PRIMARY KEY,
    topic           VARCHAR(200) NOT NULL,
    record_partition INTEGER     NOT NULL,
    record_offset   BIGINT       NOT NULL,
    record_key      VARCHAR(200),
    event_id        UUID,
    event_type      VARCHAR(100),
    payload         TEXT         NOT NULL,
    error           TEXT         NOT NULL,
    quarantined_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    replayed_at     TIMESTAMPTZ,
    replay_outcome  VARCHAR(30),
    CONSTRAINT uq_notif_quarantined_events_position UNIQUE (topic, record_partition, record_offset)
);
CREATE INDEX IF NOT EXISTS idx_notif_quarantined_events_open
    ON notif.quarantined_events (quarantined_at DESC)
    WHERE replayed_at IS NULL;
