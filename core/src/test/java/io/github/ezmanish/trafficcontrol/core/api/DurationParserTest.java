package io.github.ezmanish.trafficcontrol.core.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TC-005: Window strings 30s, 1m, 1h, 2d; invalid 1x, -1m, 0s. */
class DurationParserTest {

  @Test
  @DisplayName("TC-005: Parse valid durations")
  void testParseValidDurations() {
    assertThat(DurationParser.parse("500ms")).isEqualTo(Duration.ofMillis(500));
    assertThat(DurationParser.parse("30s")).isEqualTo(Duration.ofSeconds(30));
    assertThat(DurationParser.parse("1m")).isEqualTo(Duration.ofMinutes(1));
    assertThat(DurationParser.parse("1h")).isEqualTo(Duration.ofHours(1));
    assertThat(DurationParser.parse("2d")).isEqualTo(Duration.ofDays(2));
  }

  @ParameterizedTest
  @ValueSource(strings = {"1x", "-1m", "0", "abc", "", "10sec", "1 min"})
  @DisplayName("TC-005: Reject invalid duration formats")
  void testRejectInvalidDurations(String invalid) {
    assertThatThrownBy(() -> DurationParser.parse(invalid))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
