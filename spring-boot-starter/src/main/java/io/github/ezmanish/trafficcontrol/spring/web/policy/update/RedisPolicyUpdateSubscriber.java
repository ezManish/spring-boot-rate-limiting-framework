package io.github.ezmanish.trafficcontrol.spring.web.policy.update;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import io.github.ezmanish.trafficcontrol.spring.validation.PolicyConfigValidator;
import io.github.ezmanish.trafficcontrol.spring.validation.ValidationResult;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicyRegistry;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicySnapshot;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.pubsub.RedisPubSubListener;
import io.lettuce.core.pubsub.StatefulRedisPubSubConnection;
import java.util.Map;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens to Redis pub/sub channel tc:policies:channel for live policy updates (TC-070..075).
 * Validates snapshots before applying, rejecting malformed updates and keeping last-known-good
 * (TC-071). Ignores duplicate or older versions (TC-074) and provides sync method for polling
 * fallback (TC-073).
 */
public class RedisPolicyUpdateSubscriber implements RedisPubSubListener<String, String> {

  private static final Logger log = LoggerFactory.getLogger(RedisPolicyUpdateSubscriber.class);

  private final PolicyRegistry policyRegistry;
  private final RedisCommands<String, String> commands;
  private final PolicyConfigValidator validator;
  private final ObjectMapper objectMapper;
  private StatefulRedisPubSubConnection<String, String> pubSubConnection;

  public RedisPolicyUpdateSubscriber(
      PolicyRegistry policyRegistry,
      RedisCommands<String, String> commands,
      PolicyConfigValidator validator,
      ObjectMapper objectMapper) {
    this.policyRegistry = Objects.requireNonNull(policyRegistry, "policyRegistry cannot be null");
    this.commands = Objects.requireNonNull(commands, "RedisCommands cannot be null");
    this.validator = validator != null ? validator : new PolicyConfigValidator();
    this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
  }

  public void subscribe(StatefulRedisPubSubConnection<String, String> connection) {
    this.pubSubConnection = connection;
    connection.addListener(this);
    connection.sync().subscribe(RedisPolicyUpdateBroadcaster.CHANNEL);
    log.info("Subscribed to policy update channel: {}", RedisPolicyUpdateBroadcaster.CHANNEL);
  }

  @Override
  public void message(String channel, String message) {
    if (!RedisPolicyUpdateBroadcaster.CHANNEL.equals(channel)) {
      return;
    }
    try {
      PolicyUpdateMessage updateMsg = objectMapper.readValue(message, PolicyUpdateMessage.class);
      long currentVersion = policyRegistry.getSnapshot().version();

      // TC-074: Ignore duplicate or stale versions
      if (updateMsg.version() <= currentVersion) {
        log.debug(
            "Ignored update message with version {} (current version is {})",
            updateMsg.version(),
            currentVersion);
        return;
      }

      applyRemoteSnapshot(updateMsg.version(), updateMsg.updatedBy());
    } catch (Exception e) {
      log.error("Failed to process policy update message on channel {}: {}", channel, message, e);
    }
  }

  /** Polling fallback (TC-073): checks remote version and syncs if local is behind. */
  public boolean pollAndSync() {
    try {
      String versionStr = commands.get(RedisPolicyUpdateBroadcaster.VERSION_KEY);
      if (versionStr == null || versionStr.isBlank()) {
        return false;
      }
      long remoteVersion = Long.parseLong(versionStr.trim());
      long currentVersion = policyRegistry.getSnapshot().version();
      if (remoteVersion > currentVersion) {
        log.info(
            "Poll fallback detected newer policy version {} > {}. Syncing...",
            remoteVersion,
            currentVersion);
        return applyRemoteSnapshot(remoteVersion, "poller");
      }
      return false;
    } catch (Exception e) {
      log.warn("Policy poll fallback failed: {}", e.getMessage());
      return false;
    }
  }

  private boolean applyRemoteSnapshot(long version, String updatedBy) {
    try {
      String snapshotJson = commands.get(RedisPolicyUpdateBroadcaster.SNAPSHOT_KEY);
      if (snapshotJson == null || snapshotJson.isBlank()) {
        log.warn("Remote snapshot JSON is empty for version {}", version);
        return false;
      }

      Map<String, PolicyConfig> newPolicies =
          objectMapper.readValue(snapshotJson, new TypeReference<Map<String, PolicyConfig>>() {});

      // TC-071: Validate incoming snapshot before applying
      TrafficControlProperties tempProps = new TrafficControlProperties();
      tempProps.setPolicies(newPolicies);
      ValidationResult validation = validator.validate(tempProps);

      if (validation.hasErrors()) {
        log.error(
            "TC-071: Rejected invalid policy snapshot v{} received from {}. Validation errors: {}. Retaining active policy.",
            version,
            updatedBy,
            validation.getErrors());
        return false;
      }

      // Compile and atomically swap
      PolicySnapshot newSnapshot =
          policyRegistry.compileSnapshot(
              version, updatedBy, newPolicies, policyRegistry.getSnapshot().pathRules());

      return policyRegistry.updateSnapshot(newSnapshot);
    } catch (Exception e) {
      log.error(
          "TC-071: Failed to parse/apply policy snapshot v{}: {}. Retaining current policy.",
          version,
          e.getMessage(),
          e);
      return false;
    }
  }

  @Override
  public void message(String pattern, String channel, String message) {}

  @Override
  public void subscribed(String channel, long count) {}

  @Override
  public void psubscribed(String pattern, long count) {}

  @Override
  public void unsubscribed(String channel, long count) {}

  @Override
  public void punsubscribed(String pattern, long count) {}
}
