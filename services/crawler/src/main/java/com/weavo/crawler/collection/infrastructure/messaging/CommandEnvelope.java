package com.weavo.crawler.collection.infrastructure.messaging;

/** A task command. The payload is validated according to {@code commandType}. */
public record CommandEnvelope<T>(
    int formatVersion,
    String commandType,
    String jobId,
    String parentJobId,
    String requestedAt,
    String expiresAt,
    T payload) {}
