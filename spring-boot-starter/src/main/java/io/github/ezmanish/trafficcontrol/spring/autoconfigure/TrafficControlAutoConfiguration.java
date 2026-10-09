package io.github.ezmanish.trafficcontrol.spring.autoconfigure;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.ezmanish.trafficcontrol.core.algorithm.RateLimitAlgorithmRegistry;
import io.github.ezmanish.trafficcontrol.core.api.DefaultRateLimitEngine;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitEngine;
import io.github.ezmanish.trafficcontrol.core.spi.*;
import io.github.ezmanish.trafficcontrol.spring.validation.InvalidConfigurationException;
import io.github.ezmanish.trafficcontrol.spring.validation.PolicyConfigValidator;
import io.github.ezmanish.trafficcontrol.spring.validation.ValidationResult;
import io.github.ezmanish.trafficcontrol.spring.web.interceptor.RateLimitInterceptor;
import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicyRegistry;
import io.github.ezmanish.trafficcontrol.spring.web.proxy.ClientIpResolver;
import io.github.ezmanish.trafficcontrol.spring.web.resolver.CompositeKeyResolverService;
import io.github.ezmanish.trafficcontrol.spring.web.response.ProblemDetailsResponseWriter;
import io.github.ezmanish.trafficcontrol.spring.web.response.RateLimitHeaderWriter;
import io.github.ezmanish.trafficcontrol.store.local.LocalRateLimitStore;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/** Spring Boot AutoConfiguration for the TrafficControl rate limiting framework. */
@AutoConfiguration
@ConditionalOnProperty(
    prefix = "trafficcontrol",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@EnableConfigurationProperties(TrafficControlProperties.class)
public class TrafficControlAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(TrafficControlAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  public Clock trafficControlClock() {
    return Clock.system();
  }

  @Bean
  @ConditionalOnMissingBean
  public RateLimitAlgorithmRegistry trafficControlAlgorithmRegistry() {
    return new RateLimitAlgorithmRegistry();
  }

  @Bean
  @ConditionalOnMissingBean(RateLimitStore.class)
  @ConditionalOnProperty(
      prefix = "trafficcontrol",
      name = "store",
      havingValue = "local",
      matchIfMissing = true)
  public RateLimitStore localRateLimitStore(Clock clock, RateLimitAlgorithmRegistry registry) {
    return new LocalRateLimitStore(100_000, java.time.Duration.ofMinutes(10), registry, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public RateLimitEngine trafficControlRateLimitEngine(
      RateLimitStore store,
      Clock clock,
      Optional<FailureStrategy> failureStrategy,
      List<DecisionListener> listeners) {
    return new DefaultRateLimitEngine(
        store, clock, failureStrategy.orElse(FailureStrategy.defaultStrategy()), listeners);
  }

  @Bean
  @ConditionalOnMissingBean
  public ClientIpResolver trafficControlClientIpResolver(TrafficControlProperties properties) {
    return new ClientIpResolver(properties.getTrustedProxies());
  }

  @Bean
  @ConditionalOnMissingBean
  public CompositeKeyResolverService trafficControlCompositeKeyResolverService(
      Optional<Map<String, RateLimitKeyResolver>> customResolvers) {
    return new CompositeKeyResolverService(customResolvers.orElse(Map.of()));
  }

  @Bean
  @ConditionalOnMissingBean
  public PolicyRegistry trafficControlPolicyRegistry(TrafficControlProperties properties) {
    return new PolicyRegistry(properties);
  }

  @Bean
  @ConditionalOnMissingBean
  public RateLimitHeaderWriter trafficControlRateLimitHeaderWriter() {
    return new RateLimitHeaderWriter();
  }

  @Bean
  @ConditionalOnMissingBean
  public ProblemDetailsResponseWriter trafficControlProblemDetailsResponseWriter(
      TrafficControlProperties properties, Optional<ObjectMapper> objectMapper) {
    return new ProblemDetailsResponseWriter(objectMapper.orElse(null), properties.getProblem());
  }

  @Bean
  @ConditionalOnMissingBean
  public PolicyConfigValidator trafficControlPolicyConfigValidator() {
    return new PolicyConfigValidator();
  }

  @Bean
  @ConditionalOnMissingBean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  public RateLimitInterceptor trafficControlRateLimitInterceptor(
      TrafficControlProperties properties,
      PolicyRegistry policyRegistry,
      RateLimitEngine rateLimitEngine,
      RateLimitStore rateLimitStore,
      ClientIpResolver clientIpResolver,
      CompositeKeyResolverService keyResolverService,
      RateLimitHeaderWriter headerWriter,
      ProblemDetailsResponseWriter problemWriter,
      Optional<Map<String, PlanResolver>> planResolvers) {
    return new RateLimitInterceptor(
        properties,
        policyRegistry,
        rateLimitEngine,
        rateLimitStore,
        clientIpResolver,
        keyResolverService,
        headerWriter,
        problemWriter,
        planResolvers.orElse(Map.of()));
  }

  @Bean
  @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
  public WebMvcConfigurer trafficControlWebMvcConfigurer(RateLimitInterceptor interceptor) {
    return new WebMvcConfigurer() {
      @Override
      public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(interceptor).addPathPatterns("/**");
      }
    };
  }

  @Bean
  public SmartInitializingSingleton trafficControlStartupValidator(
      TrafficControlProperties properties,
      PolicyConfigValidator validator,
      ApplicationContext applicationContext) {
    return () -> {
      boolean hasSecurity = false;
      try {
        hasSecurity =
            applicationContext.containsBean("securityFilterChain")
                || applicationContext.getBeanNamesForType(
                            Class.forName("org.springframework.security.web.SecurityFilterChain"))
                        .length
                    > 0;
      } catch (Throwable ignored) {
      }
      ValidationResult result = validator.validate(properties, hasSecurity);

      // Scan handler mappings for annotations
      try {
        RequestMappingHandlerMapping mapping =
            applicationContext.getBean(RequestMappingHandlerMapping.class);
        Map<org.springframework.web.servlet.mvc.method.RequestMappingInfo, HandlerMethod>
            handlerMethods = mapping.getHandlerMethods();
        for (HandlerMethod hm : handlerMethods.values()) {
          validator.validateClass(hm.getBeanType(), properties.getPolicies(), result);
          validator.validateMethod(
              hm.getMethod(), hm.getBeanType(), properties.getPolicies(), result);
        }
      } catch (Exception ignored) {
        // Not in a web context or mapping not yet initialized
      }

      // Print warnings
      for (var warn : result.getWarnings()) {
        log.warn("[{}] {}: {}", warn.code(), warn.path(), warn.message());
      }

      if (result.hasErrors()) {
        throw new InvalidConfigurationException(result);
      }
    };
  }
}
