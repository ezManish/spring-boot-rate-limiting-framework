package io.github.ezmanish.trafficcontrol.spring.annotation;

import static java.lang.annotation.RetentionPolicy.RUNTIME;

import io.github.ezmanish.trafficcontrol.core.api.Algorithm;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** Declares a single rate limit rule within a multi-rule @RateLimit annotation. */
@Target({})
@Retention(RUNTIME)
public @interface Limit {

  /** Unique identifier for this rule within the policy (e.g. "r1", "per-second"). */
  String id() default "";

  /** Number of allowed requests/permits per window. */
  long requests();

  /** Window duration string (e.g. "1s", "1m", "1h"). */
  String window();

  /** Maximum burst capacity for Token Bucket or GCRA (0 = defaults to requests). */
  long burst() default 0;

  /** Algorithm to evaluate this rule. */
  Algorithm algorithm() default Algorithm.TOKEN_BUCKET;
}
