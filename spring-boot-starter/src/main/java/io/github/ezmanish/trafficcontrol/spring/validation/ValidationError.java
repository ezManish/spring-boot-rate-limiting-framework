package io.github.ezmanish.trafficcontrol.spring.validation;

/**
 * Represents a single configuration validation issue matching rules V-001..V-030.
 *
 * @param code validation rule code (e.g. V-001, V-006)
 * @param severity ERROR (fails startup) or WARNING (logged)
 * @param path configuration property path or annotated method
 * @param message actionable explanation
 */
public record ValidationError(
    String code, ValidationSeverity severity, String path, String message) {}
