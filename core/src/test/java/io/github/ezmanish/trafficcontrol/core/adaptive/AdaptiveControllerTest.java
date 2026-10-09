package io.github.ezmanish.trafficcontrol.core.adaptive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class AdaptiveControllerTest {

  private AdaptiveController controller;

  @BeforeEach
  void setUp() {
    controller = new AdaptiveController(0.25, 50.0, true);
  }

  @Test
  @DisplayName(
      "TC-090: 2 consecutive overloaded intervals decrease effective limit by 0.7x (Multiplicative Decrease)")
  void testOverloadDecreasesLimit() {
    long now = 1000L;
    AdaptiveSignals overloaded = new AdaptiveSignals(0.90, 0.50, 40.0, 0.0);

    // Interval 1: 1 overloaded interval (no decrease yet, requires 2)
    controller.evaluateInterval(overloaded, now);
    assertThat(controller.getMultiplier()).isEqualTo(1.0);

    // Interval 2: 2nd consecutive overloaded interval -> Multiplicative Decrease (1.0 * 0.7 = 0.7)
    controller.evaluateInterval(overloaded, now + 5000L);
    assertThat(controller.getMultiplier()).isCloseTo(0.70, within(0.001));

    // Request limit scales down
    assertThat(controller.scaleRequests(100)).isEqualTo(70);
  }

  @Test
  @DisplayName(
      "TC-091: 6 consecutive healthy intervals increase limit by +0.05 (Additive Increase)")
  void testRecoveryIncreasesLimitGradually() {
    controller.setMultiplier(0.70);
    long now = 1000L;
    AdaptiveSignals healthy = new AdaptiveSignals(0.50, 0.40, 20.0, 0.0);

    // 5 healthy intervals: no change
    for (int i = 1; i <= 5; i++) {
      controller.evaluateInterval(healthy, now + i * 5000L);
      assertThat(controller.getMultiplier()).isCloseTo(0.70, within(0.001));
    }

    // 6th healthy interval -> Additive Increase (0.70 + 0.05 = 0.75)
    controller.evaluateInterval(healthy, now + 6 * 5000L);
    assertThat(controller.getMultiplier()).isCloseTo(0.75, within(0.001));
  }

  @Test
  @DisplayName("TC-092: Multiplier never drops below min-multiplier (0.25) or exceeds 1.0")
  void testBoundsEnforced() {
    long now = 1000L;
    AdaptiveSignals severeOverload = new AdaptiveSignals(0.99, 0.99, 200.0, 0.5);

    // Repeated overload intervals past cooldown
    for (int i = 1; i <= 20; i++) {
      controller.evaluateInterval(severeOverload, now + i * 20000L);
    }

    // Must clamp to minMultiplier (0.25)
    assertThat(controller.getMultiplier()).isCloseTo(0.25, within(0.001));
    assertThat(controller.scaleRequests(100)).isEqualTo(25);

    // Now repeated healthy intervals to reach 1.0
    AdaptiveSignals healthy = new AdaptiveSignals(0.10, 0.10, 10.0, 0.0);
    for (int i = 1; i <= 100; i++) {
      controller.evaluateInterval(healthy, now + 500000L + i * 5000L);
    }
    assertThat(controller.getMultiplier()).isCloseTo(1.0, within(0.001));
  }

  @Test
  @DisplayName("TC-093: Freeze and disabled safeguards prevent changes")
  void testSafeguards() {
    controller.setMultiplier(0.60);
    controller.setFrozen(true);

    AdaptiveSignals overloaded = new AdaptiveSignals(0.99, 0.99, 200.0, 0.5);
    controller.evaluateInterval(overloaded, 1000L);
    controller.evaluateInterval(overloaded, 6000L);

    // Remains frozen at 0.60
    assertThat(controller.getMultiplier()).isEqualTo(0.60);

    controller.setFrozen(false);
    controller.setEnabled(false);
    assertThat(controller.scaleRequests(100)).isEqualTo(100); // Unscaled when disabled
  }
}
