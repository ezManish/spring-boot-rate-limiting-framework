package io.github.ezmanish.trafficcontrol.spring.validation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.diagnostics.FailureAnalysis;

class TrafficControlFailureAnalyzerTest {

  @Test
  void testFailureAnalysisFormatting() {
    ValidationResult result = new ValidationResult();
    result.addError("V-006", "policies.login.rules[0].window", "'1x' is not a valid duration");
    result.addWarning("V-028", "policies.login", "Two identical rules in policy");

    InvalidConfigurationException exception = new InvalidConfigurationException(result);
    TrafficControlFailureAnalyzer analyzer = new TrafficControlFailureAnalyzer();

    FailureAnalysis analysis = analyzer.analyze(exception);
    assertThat(analysis).isNotNull();
    assertThat(analysis.getDescription())
        .contains("Invalid trafficcontrol configuration (1 errors, 1 warnings):");
    assertThat(analysis.getDescription())
        .contains("[V-006] policies.login.rules[0].window: '1x' is not a valid duration");
    assertThat(analysis.getAction())
        .isEqualTo("Fix the configuration properties above and restart.");
  }
}
