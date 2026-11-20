package com.weavo.crawler.collection.infrastructure.messaging;

/** An event emitted by a worker. The execution attempt belongs to its payload. */
public record EventEnvelope<T>(
    int formatVersion,
    String eventType,
    String eventId,
    String jobId,
    String occurredAt,
    T payload) {}
