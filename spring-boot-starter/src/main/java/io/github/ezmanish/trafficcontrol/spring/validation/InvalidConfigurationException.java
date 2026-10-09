package io.github.ezmanish.trafficcontrol.spring.validation;

/** Thrown when TrafficControl configuration validation encounters errors (V-001..V-030). */
public class InvalidConfigurationException extends RuntimeException {

  private final ValidationResult validationResult;

  public InvalidConfigurationException(ValidationResult validationResult) {
    super(validationResult.formatReport());
    this.validationResult = validationResult;
  }

  public ValidationResult getValidationResult() {
    return validationResult;
  }
}
