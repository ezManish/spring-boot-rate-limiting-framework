package io.github.ezmanish.trafficcontrol.spring.web.admin;

import java.time.Instant;

/** Record representing an audit log entry for a policy change (05_API_SPECIFICATION.md §5). */
public record PolicyAuditEntry(
    String id,
    long version,
    Instant timestamp,
    String actor,
    String action,
    String policy,
    String before,
    String after) {}
