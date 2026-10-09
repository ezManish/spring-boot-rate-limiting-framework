package io.github.ezmanish.trafficcontrol.spring.annotation;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.TYPE;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

/** References a named policy declared in application.yml (e.g. @RateLimitPolicy("login")). */
@Target({METHOD, TYPE})
@Retention(RUNTIME)
@Documented
public @interface RateLimitPolicy {

  /** Name of the policy configured in application.yml. */
  String value();
}
