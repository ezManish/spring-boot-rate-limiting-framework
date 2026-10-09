package io.github.ezmanish.trafficcontrol.spring.web.resolver;

/**
 * Thrown when on-missing-key is set to REJECT and no client identity could be resolved. Yields an
 * RFC 9457 HTTP 429 response with kind 'identity-required'.
 */
public class MissingIdentityException extends RuntimeException {

  public MissingIdentityException(String message) {
    super(message);
  }
}
