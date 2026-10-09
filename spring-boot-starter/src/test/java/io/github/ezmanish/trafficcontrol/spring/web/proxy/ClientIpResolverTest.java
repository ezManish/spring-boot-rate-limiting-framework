package io.github.ezmanish.trafficcontrol.spring.web.proxy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class ClientIpResolverTest {

  @Test
  @DisplayName("Direct client without proxy uses remoteAddr")
  void testDirectClient() {
    ClientIpResolver resolver = new ClientIpResolver(List.of());
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("203.0.113.195");

    String ip = resolver.resolveClientIp(request);
    assertThat(ip).isEqualTo("203.0.113.195");
  }

  @Test
  @DisplayName("SEC-01: Untrusted remote address ignores X-Forwarded-For header")
  void testUntrustedRemoteAddressIgnoresXForwardedFor() {
    ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("198.51.100.1"); // Not in 10.0.0.0/8!
    request.addHeader("X-Forwarded-For", "203.0.113.50, 10.0.0.1");

    String ip = resolver.resolveClientIp(request);
    // Untrusted caller cannot spoof client IP via XFF
    assertThat(ip).isEqualTo("198.51.100.1");
  }

  @Test
  @DisplayName("Trusted proxy correctly extracts rightmost untrusted hop from X-Forwarded-For")
  void testTrustedProxyExtractsClientIp() {
    ClientIpResolver resolver = new ClientIpResolver(List.of("10.0.0.0/8", "172.16.0.0/12"));
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setRemoteAddr("10.0.0.2"); // Trusted proxy
    request.addHeader("X-Forwarded-For", "198.51.100.5, 172.16.5.10");

    // 172.16.5.10 is trusted, 198.51.100.5 is the rightmost untrusted client IP
    String ip = resolver.resolveClientIp(request);
    assertThat(ip).isEqualTo("198.51.100.5");
  }
}
