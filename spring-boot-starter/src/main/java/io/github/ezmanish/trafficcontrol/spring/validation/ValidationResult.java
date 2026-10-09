package io.github.ezmanish.trafficcontrol.spring.validation;

import java.util.ArrayList;
import java.util.List;

/** Aggregates all configuration validation errors and warnings. */
public class ValidationResult {

  private final List<ValidationError> errors = new ArrayList<>();

  public void add(String code, ValidationSeverity severity, String path, String message) {
    errors.add(new ValidationError(code, severity, path, message));
  }

  public void addError(String code, String path, String message) {
    add(code, ValidationSeverity.ERROR, path, message);
  }

  public void addWarning(String code, String path, String message) {
    add(code, ValidationSeverity.WARNING, path, message);
  }

  public List<ValidationError> getErrors() {
    return errors.stream().filter(e -> e.severity() == ValidationSeverity.ERROR).toList();
  }

  public List<ValidationError> getWarnings() {
    return errors.stream().filter(e -> e.severity() == ValidationSeverity.WARNING).toList();
  }

  public List<ValidationError> getAll() {
    return List.copyOf(errors);
  }

  public boolean hasErrors() {
    return errors.stream().anyMatch(e -> e.severity() == ValidationSeverity.ERROR);
  }

  public String formatReport() {
    int errCount = getErrors().size();
    int warnCount = getWarnings().size();
    StringBuilder sb = new StringBuilder();
    sb.append(
        String.format(
            "Invalid trafficcontrol configuration (%d errors, %d warnings):\n",
            errCount, warnCount));
    for (ValidationError issue : errors) {
      sb.append(String.format("  [%s] %s: %s\n", issue.code(), issue.path(), issue.message()));
    }
    sb.append("Action: fix the properties above and restart.");
    return sb.toString();
  }
}
