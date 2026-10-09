package io.github.ezmanish.trafficcontrol.spring.web.policy.update;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicySnapshot;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes live policy snapshots across Redis Cluster instances via Pub/Sub (TR-07, TC-070..075).
 */
public class RedisPolicyUpdateBroadcaster implements PolicyUpdateBroadcaster {

  private static final Logger log = LoggerFactory.getLogger(RedisPolicyUpdateBroadcaster.class);

  public static final String CHANNEL = "tc:policies:channel";
  public static final String VERSION_KEY = "tc:policies:version";
  public static final String SNAPSHOT_KEY = "tc:policies:snapshot";

  private final RedisCommands<String, String> commands;
  private final ObjectMapper objectMapper;

  public RedisPolicyUpdateBroadcaster(
      RedisCommands<String, String> commands, ObjectMapper objectMapper) {
    this.commands = Objects.requireNonNull(commands, "RedisCommands cannot be null");
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
  }

  @Override
  public void broadcastUpdate(PolicySnapshot snapshot, String rawJson) {
    Objects.requireNonNull(snapshot, "PolicySnapshot cannot be null");
    try {
      // 1. Store version and snapshot body in Redis
      commands.set(VERSION_KEY, String.valueOf(snapshot.version()));
      if (rawJson != null) {
        commands.set(SNAPSHOT_KEY, rawJson);
      }

      // 2. Publish notification to pub/sub channel
      PolicyUpdateMessage message =
          new PolicyUpdateMessage(
              snapshot.version(),
              snapshot.checksum(),
              snapshot.updatedAt().toEpochMilli(),
              snapshot.updatedBy());
      String serializedMessage = objectMapper.writeValueAsString(message);
      commands.publish(CHANNEL, serializedMessage);

      log.info(
          "Broadcasted policy update v{} on {} (checksum={})",
          snapshot.version(),
          CHANNEL,
          snapshot.checksum());
    } catch (Exception e) {
      log.error("Failed to broadcast policy update v{} via Redis", snapshot.version(), e);
      throw new RuntimeException("Failed to broadcast policy update: " + e.getMessage(), e);
    }
  }
}
