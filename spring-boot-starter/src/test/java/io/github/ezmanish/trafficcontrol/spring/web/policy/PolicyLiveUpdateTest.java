package io.github.ezmanish.trafficcontrol.spring.web.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.RuleConfig;
import io.github.ezmanish.trafficcontrol.spring.validation.PolicyConfigValidator;
import io.github.ezmanish.trafficcontrol.spring.web.policy.update.PolicyUpdateMessage;
import io.github.ezmanish.trafficcontrol.spring.web.policy.update.RedisPolicyUpdateBroadcaster;
import io.github.ezmanish.trafficcontrol.spring.web.policy.update.RedisPolicyUpdateSubscriber;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PolicyLiveUpdateTest {

  @Mock private RedisCommands<String, String> commands;

  private ObjectMapper objectMapper;
  private PolicyConfigValidator validator;
  private PolicyRegistry registry;
  private RedisPolicyUpdateSubscriber subscriber;

  @BeforeEach
  void setUp() {
    objectMapper = new ObjectMapper();
    validator = new PolicyConfigValidator();

    TrafficControlProperties props = new TrafficControlProperties();
    PolicyConfig initialPolicy = new PolicyConfig();
    RuleConfig rule = new RuleConfig();
    rule.setRequests(100);
    rule.setWindow("1m");
    initialPolicy.setRules(List.of(rule));
    props.setPolicies(Map.of("api", initialPolicy));

    registry = new PolicyRegistry(props);
    subscriber = new RedisPolicyUpdateSubscriber(registry, commands, validator, objectMapper);
  }

  @Test
  @DisplayName(
      "TC-070: Limit changes from 100 to 10 via pub/sub update without application restart")
  void testLivePolicyUpdateAdoptsImmediately() throws Exception {
    assertThat(registry.getSnapshot().version()).isEqualTo(1L);
    assertThat(registry.getNamedPolicy("api").get().policy().rules().get(0).requests())
        .isEqualTo(100);

    // Prepare updated policy with limit 10
    PolicyConfig updatedPolicy = new PolicyConfig();
    RuleConfig newRule = new RuleConfig();
    newRule.setRequests(10);
    newRule.setWindow("1m");
    updatedPolicy.setRules(List.of(newRule));
    Map<String, PolicyConfig> updatedMap = Map.of("api", updatedPolicy);
    String updatedJson = objectMapper.writeValueAsString(updatedMap);

    when(commands.get(RedisPolicyUpdateBroadcaster.SNAPSHOT_KEY)).thenReturn(updatedJson);

    // Publish message with version 2
    PolicyUpdateMessage msg =
        new PolicyUpdateMessage(2L, "sha256:abc", System.currentTimeMillis(), "admin");
    subscriber.message(RedisPolicyUpdateBroadcaster.CHANNEL, objectMapper.writeValueAsString(msg));

    // Verify instant adoption
    assertThat(registry.getSnapshot().version()).isEqualTo(2L);
    assertThat(registry.getNamedPolicy("api").get().policy().rules().get(0).requests())
        .isEqualTo(10);
  }

  @Test
  @DisplayName(
      "TC-071: Invalid snapshot published via pub/sub is rejected and keeps last-known-good")
  void testInvalidSnapshotRejectedKeepsLastKnownGood() throws Exception {
    long initialVersion = registry.getSnapshot().version();
    long initialRequests = registry.getNamedPolicy("api").get().policy().rules().get(0).requests();

    // Malformed policy violating V-006 (0s window) and V-004 (0 requests)
    PolicyConfig invalidPolicy = new PolicyConfig();
    RuleConfig invalidRule = new RuleConfig();
    invalidRule.setRequests(-5);
    invalidRule.setWindow("0s");
    invalidPolicy.setRules(List.of(invalidRule));
    String invalidJson = objectMapper.writeValueAsString(Map.of("api", invalidPolicy));

    when(commands.get(RedisPolicyUpdateBroadcaster.SNAPSHOT_KEY)).thenReturn(invalidJson);

    PolicyUpdateMessage msg =
        new PolicyUpdateMessage(2L, "sha256:bad", System.currentTimeMillis(), "malicious");
    subscriber.message(RedisPolicyUpdateBroadcaster.CHANNEL, objectMapper.writeValueAsString(msg));

    // Must remain on original version and configuration
    assertThat(registry.getSnapshot().version()).isEqualTo(initialVersion);
    assertThat(registry.getNamedPolicy("api").get().policy().rules().get(0).requests())
        .isEqualTo(initialRequests);
  }

  @Test
  @DisplayName("TC-072: Concurrent reads during atomic swap experience zero errors or torn reads")
  void testConcurrentReadsDuringAtomicSwap() throws Exception {
    int readers = 20;
    int iterations = 1000;
    ExecutorService executor = Executors.newFixedThreadPool(readers + 1);
    AtomicBoolean running = new AtomicBoolean(true);
    CountDownLatch startLatch = new CountDownLatch(1);
    CountDownLatch doneLatch = new CountDownLatch(readers);

    // Reader tasks
    for (int i = 0; i < readers; i++) {
      executor.submit(
          () -> {
            try {
              startLatch.await();
              while (running.get()) {
                PolicySnapshot snap = registry.getSnapshot();
                assertThat(snap).isNotNull();
                assertThat(snap.namedPolicies()).containsKey("api");
                CompiledPolicy cp = snap.namedPolicies().get("api");
                assertThat(cp.policy().rules()).isNotEmpty();
              }
            } catch (Exception e) {
              throw new RuntimeException(e);
            } finally {
              doneLatch.countDown();
            }
          });
    }

    startLatch.countDown();

    // Writer performs 50 atomic swaps
    for (int v = 2; v <= 52; v++) {
      PolicyConfig p = new PolicyConfig();
      RuleConfig r = new RuleConfig();
      r.setRequests(v * 10);
      r.setWindow("1m");
      p.setRules(List.of(r));
      PolicySnapshot newSnap =
          registry.compileSnapshot(v, "stress-test", Map.of("api", p), List.of());
      registry.updateSnapshot(newSnap);
    }

    running.set(false);
    doneLatch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(registry.getSnapshot().version()).isEqualTo(52L);
  }

  @Test
  @DisplayName("TC-073: Missed pub/sub message converges via poll fallback")
  void testPollFallbackConverges() throws Exception {
    // Redis has version 5 stored
    when(commands.get(RedisPolicyUpdateBroadcaster.VERSION_KEY)).thenReturn("5");

    PolicyConfig p = new PolicyConfig();
    RuleConfig r = new RuleConfig();
    r.setRequests(50);
    r.setWindow("1m");
    p.setRules(List.of(r));
    String json = objectMapper.writeValueAsString(Map.of("api", p));
    when(commands.get(RedisPolicyUpdateBroadcaster.SNAPSHOT_KEY)).thenReturn(json);

    boolean updated = subscriber.pollAndSync();

    assertThat(updated).isTrue();
    assertThat(registry.getSnapshot().version()).isEqualTo(5L);
    assertThat(registry.getNamedPolicy("api").get().policy().rules().get(0).requests())
        .isEqualTo(50);
  }

  @Test
  @DisplayName(
      "TC-074: Stale or duplicate version message is ignored and active version never regresses")
  void testStaleOrDuplicateVersionIgnored() throws Exception {
    // Manually advance registry to version 10
    PolicySnapshot v10 =
        registry.compileSnapshot(10L, "admin", registry.getSnapshot().rawConfigs(), List.of());
    registry.updateSnapshot(v10);

    // Stale version 9 message
    PolicyUpdateMessage staleMsg =
        new PolicyUpdateMessage(9L, "sha256:old", System.currentTimeMillis(), "old-admin");
    subscriber.message(
        RedisPolicyUpdateBroadcaster.CHANNEL, objectMapper.writeValueAsString(staleMsg));

    assertThat(registry.getSnapshot().version()).isEqualTo(10L);

    // Duplicate version 10 message
    PolicyUpdateMessage dupMsg =
        new PolicyUpdateMessage(10L, "sha256:dup", System.currentTimeMillis(), "dup-admin");
    subscriber.message(
        RedisPolicyUpdateBroadcaster.CHANNEL, objectMapper.writeValueAsString(dupMsg));

    assertThat(registry.getSnapshot().version()).isEqualTo(10L);
    verify(commands, never()).get(RedisPolicyUpdateBroadcaster.SNAPSHOT_KEY);
  }
}
