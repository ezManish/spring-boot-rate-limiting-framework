package io.github.ezmanish.trafficcontrol.spring.validation;

import org.springframework.boot.diagnostics.AbstractFailureAnalyzer;
import org.springframework.boot.diagnostics.FailureAnalysis;

/**
 * FailureAnalyzer that formats TrafficControl configuration validation errors into a clean,
 * actionable report instead of a noisy stack trace.
 */
public class TrafficControlFailureAnalyzer
    extends AbstractFailureAnalyzer<InvalidConfigurationException> {

  @Override
  protected FailureAnalysis analyze(Throwable rootFailure, InvalidConfigurationException cause) {
    String description = cause.getValidationResult().formatReport();
    String action = "Fix the configuration properties above and restart.";
    return new FailureAnalysis(description, action, cause);
  }
}
