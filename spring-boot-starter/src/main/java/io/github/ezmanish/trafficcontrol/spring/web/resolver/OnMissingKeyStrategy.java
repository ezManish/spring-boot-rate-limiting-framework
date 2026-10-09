package io.github.ezmanish.trafficcontrol.spring.web.resolver;

/** Behavior when the configured client identity key cannot be resolved. */
public enum OnMissingKeyStrategy {
  FALLBACK_IP,
  ANONYMOUS,
  SKIP,
  REJECT
}
