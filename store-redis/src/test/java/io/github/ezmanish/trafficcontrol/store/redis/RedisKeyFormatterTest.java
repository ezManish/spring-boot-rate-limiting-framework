package io.github.ezmanish.trafficcontrol.store.redis;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RedisKeyFormatterTest {

  @Test
  @DisplayName("TC-023: Rule key contains {clientHash} curly braces for Redis Cluster hash tagging")
  void testRuleKeyHashTag() {
    String key = RedisKeyFormatter.formatRuleKey("a1b2c3d4", "login", "per-minute");
    assertThat(key).isEqualTo("tc:{a1b2c3d4}:login:per-minute");
  }

  @Test
  @DisplayName(
      "TC-023: Concurrency key shares identical {clientHash} hash tag to prevent CROSSSLOT")
  void testConcurrencyKeyHashTag() {
    String key = RedisKeyFormatter.formatConcurrencyKey("a1b2c3d4", "login");
    assertThat(key).isEqualTo("tc:{a1b2c3d4}:login:conc");
  }
}
