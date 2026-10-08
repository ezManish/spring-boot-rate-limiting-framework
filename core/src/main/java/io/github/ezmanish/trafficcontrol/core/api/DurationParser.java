package io.github.ezmanish.trafficcontrol.core.api;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses duration strings like "30s", "1m", "1h", "2d", "500ms" matching
 * docs/06_POLICY_SPECIFICATION.md.
 */
public final class DurationParser {

  private static final Pattern PATTERN = Pattern.compile("^(\\d+)(ms|s|m|h|d)$");

  private DurationParser() {}

  public static Duration parse(String text) {
    if (text == null) {
      throw new IllegalArgumentException("Duration string cannot be null");
    }
    String trimmed = text.trim();
    Matcher matcher = PATTERN.matcher(trimmed);
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          "Invalid duration format: '" + text + "'. Expected format: ^\\d+(ms|s|m|h|d)$");
    }

    long amount = Long.parseLong(matcher.group(1));
    String unit = matcher.group(2);

    return switch (unit) {
      case "ms" -> Duration.ofMillis(amount);
      case "s" -> Duration.ofSeconds(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      case "d" -> Duration.ofDays(amount);
      default -> throw new IllegalArgumentException("Unknown unit: " + unit);
    };
  }
}
