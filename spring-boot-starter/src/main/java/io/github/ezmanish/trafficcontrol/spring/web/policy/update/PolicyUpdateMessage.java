package io.github.ezmanish.trafficcontrol.spring.web.policy.update;

/** Message payload published on Redis pub/sub channel tc:policies:channel (TR-07, TC-070..075). */
public record PolicyUpdateMessage(
    long version, String checksum, long timestamp, String updatedBy) {}
