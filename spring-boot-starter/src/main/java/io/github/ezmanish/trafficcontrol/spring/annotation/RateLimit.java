package io.github.ezmanish.trafficcontrol.spring.annotation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import io.github.ezmanish.trafficcontrol.core.api.RateLimitKey;
import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Declares inline rate limit rules for an endpoint or controller class. */
@Target({METHOD, TYPE})
@Retention(RUNTIME)
@Documented
public @interface RateLimit {

  /** Number of allowed requests per window (for single-rule usage). */
  long requests() default 0;

  /** Window duration string (e.g. "30s", "1m", "1h"). */
  String window() default "";

  /** Identity key type to identify clients. Default is USER. */
  RateLimitKey key() default RateLimitKey.USER;

  /** Key components when key = COMPOSITE (2 to 3 entries). */
  RateLimitKey[] components() default {};

  /** Default algorithm for this policy. Default is TOKEN_BUCKET. */
  Algorithm algorithm() default Algorithm.TOKEN_BUCKET;

  /** Multiple rules (e.g. per-second and per-hour limits). */
  Limit[] rules() default {};

  /** Optional SpEL expression for custom dynamic keys. */
  String keyExpression() default "";

  /** Failure mode override: "FAIL_OPEN", "FAIL_CLOSED", or empty for global default. */
  String onFailure() default "";
}
