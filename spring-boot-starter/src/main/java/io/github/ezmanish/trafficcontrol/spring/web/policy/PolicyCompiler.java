package io.github.ezmanish.trafficcontrol.spring.web.policy;

import io.github.ezmanish.trafficcontrol.core.api.*;
import io.github.ezmanish.trafficcontrol.spring.annotation.Limit;
import io.github.ezmanish.trafficcontrol.spring.annotation.RateLimit;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.ConcurrencyConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PlanConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.PolicyConfig;
import io.github.ezmanish.trafficcontrol.spring.autoconfigure.TrafficControlProperties.RuleConfig;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.OnMissingKeyStrategy;
import java.time.Duration;
import java.util.*;

/** Compiles configuration properties and @RateLimit annotations into CompiledPolicy instances. */
public class PolicyCompiler {

  public static CompiledPolicy compile(String name, PolicyConfig config, FailMode globalFailMode) {
    FailMode failMode = config.getFailMode() != null ? config.getFailMode() : globalFailMode;
    Algorithm defaultAlgo =
        config.getAlgorithm() != null ? config.getAlgorithm() : Algorithm.TOKEN_BUCKET;

    List<RateRule> rateRules = new ArrayList<>();
    if (config.getRules() != null) {
      for (int i = 0; i < config.getRules().size(); i++) {
        RuleConfig rc = config.getRules().get(i);
        String id = rc.getId() != null && !rc.getId().isBlank() ? rc.getId() : "r" + (i + 1);
        Algorithm algo = rc.getAlgorithm() != null ? rc.getAlgorithm() : defaultAlgo;
        long burst = rc.getBurst() > 0 ? rc.getBurst() : rc.getRequests();
        Duration window = DurationParser.parse(rc.getWindow());
        rateRules.add(new RateRule(id, algo, rc.getRequests(), window, burst));
      }
    }

    ConcurrencyRule concurrencyRule = null;
    ConcurrencyConfig cc = config.getConcurrency();
    if (cc != null && cc.getMax() > 0) {
      Duration wait = cc.getWait() != null ? DurationParser.parse(cc.getWait()) : Duration.ZERO;
      Duration leaseTtl =
          cc.getLeaseTtl() != null
              ? DurationParser.parse(cc.getLeaseTtl())
              : Duration.ofSeconds(30);
      concurrencyRule = new ConcurrencyRule(cc.getMax(), wait, leaseTtl);
    }

    Map<String, PlanOverride> plans = new LinkedHashMap<>();
    if (config.getPlans() != null) {
      for (Map.Entry<String, PlanConfig> entry : config.getPlans().entrySet()) {
        PlanConfig pc = entry.getValue();
        Algorithm algo = pc.getAlgorithm() != null ? pc.getAlgorithm() : defaultAlgo;
        long burst = pc.getBurst() > 0 ? pc.getBurst() : pc.getRequests();
        Duration window = DurationParser.parse(pc.getWindow());
        plans.put(
            entry.getKey(),
            new PlanOverride(
                List.of(
                    new RateRule(
                        "plan-" + entry.getKey(), algo, pc.getRequests(), window, burst))));
      }
    }

    AdaptiveConfig adaptiveConfig = AdaptiveConfig.disabled();
    if (config.getAdaptive() != null && config.getAdaptive().isEnabled()) {
      Duration targetP95 = DurationParser.parse(config.getAdaptive().getTargetP95());
      adaptiveConfig = new AdaptiveConfig(true, config.getAdaptive().getMinMultiplier(), targetP95);
    }

    RateLimitKey key = config.getKey() != null ? config.getKey() : RateLimitKey.USER;
    List<RateLimitKey> components =
        config.getComponents() != null ? config.getComponents() : List.of();

    Policy policy =
        new Policy(
            name,
            key,
            components,
            defaultAlgo,
            failMode,
            rateRules,
            concurrencyRule,
            plans,
            adaptiveConfig);

    String tenantSource = config.getTenant() != null ? config.getTenant().getSource() : "CLAIM";
    String tenantName = config.getTenant() != null ? config.getTenant().getName() : "tenant_id";

    return new CompiledPolicy(
        policy,
        key,
        components,
        config.getApiKeyHeader(),
        config.getClaim(),
        tenantSource,
        tenantName,
        config.getKeyResolver(),
        config.getPlanResolver(),
        config.getOnMissingKey());
  }

  public static CompiledPolicy compileFromAnnotation(
      String name, RateLimit rateLimit, FailMode globalFailMode) {
    FailMode failMode = globalFailMode;
    if (rateLimit.onFailure() != null && !rateLimit.onFailure().isBlank()) {
      failMode = FailMode.valueOf(rateLimit.onFailure().trim().toUpperCase());
    }

    List<RateRule> rateRules = new ArrayList<>();
    if (rateLimit.rules() != null && rateLimit.rules().length > 0) {
      for (int i = 0; i < rateLimit.rules().length; i++) {
        Limit limit = rateLimit.rules()[i];
        String id = limit.id() != null && !limit.id().isBlank() ? limit.id() : "r" + (i + 1);
        long burst = limit.burst() > 0 ? limit.burst() : limit.requests();
        Duration window = DurationParser.parse(limit.window());
        rateRules.add(new RateRule(id, limit.algorithm(), limit.requests(), window, burst));
      }
    } else {
      long requests = rateLimit.requests();
      Duration window = DurationParser.parse(rateLimit.window());
      long burst = requests;
      rateRules.add(new RateRule("r1", rateLimit.algorithm(), requests, window, burst));
    }

    List<RateLimitKey> components = Arrays.asList(rateLimit.components());

    Policy policy =
        new Policy(
            name,
            rateLimit.key(),
            components,
            rateLimit.algorithm(),
            failMode,
            rateRules,
            null,
            Map.of(),
            AdaptiveConfig.disabled());

    return new CompiledPolicy(
        policy,
        rateLimit.key(),
        components,
        "X-API-Key",
        "sub",
        "CLAIM",
        "tenant_id",
        null,
        null,
        OnMissingKeyStrategy.FALLBACK_IP);
  }
}
