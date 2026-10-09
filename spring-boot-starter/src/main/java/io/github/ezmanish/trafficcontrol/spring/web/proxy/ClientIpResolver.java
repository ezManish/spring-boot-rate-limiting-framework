package io.github.ezmanish.trafficcontrol.spring.web.proxy;

import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;

/**
 * Extracts client IP address honoring trusted proxies to prevent X-Forwarded-For spoofing (SEC-01).
 */
public class ClientIpResolver {

  private final CidrMatcher trustedProxiesMatcher;

  public ClientIpResolver(List<String> trustedProxies) {
    this.trustedProxiesMatcher = new CidrMatcher(trustedProxies);
  }

  public ClientIpResolver() {
    this(List.of());
  }

  public String resolveClientIp(HttpServletRequest request) {
    String remoteAddr = request.getRemoteAddr();
    if (remoteAddr == null) {
      return "127.0.0.1";
    }

    // If the immediate connection does not come from a trusted proxy, do NOT trust X-Forwarded-For
    if (!trustedProxiesMatcher.matches(remoteAddr)) {
      return remoteAddr;
    }

    String xff = request.getHeader("X-Forwarded-For");
    if (xff == null || xff.isBlank()) {
      return remoteAddr;
    }

    // Parse right-to-left
    String[] parts = xff.split(",");
    List<String> hops = new ArrayList<>();
    for (String part : parts) {
      String trimmed = part.trim();
      if (!trimmed.isEmpty()) {
        hops.add(trimmed);
      }
    }

    if (hops.isEmpty()) {
      return remoteAddr;
    }

    // Walk backwards from rightmost hop to find first untrusted IP
    for (int i = hops.size() - 1; i >= 0; i--) {
      String hop = hops.get(i);
      if (!trustedProxiesMatcher.matches(hop)) {
        return hop;
      }
    }

    // If all hops are trusted, return the earliest (leftmost) hop
    return hops.get(0);
  }
}
