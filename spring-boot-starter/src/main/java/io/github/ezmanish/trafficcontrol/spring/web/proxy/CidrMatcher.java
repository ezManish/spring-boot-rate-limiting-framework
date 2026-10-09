package io.github.ezmanish.trafficcontrol.spring.web.proxy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;

/**
 * Validates and matches IP addresses against configured CIDR blocks (e.g. "10.0.0.0/8",
 * "192.168.1.0/24").
 */
public class CidrMatcher {

  private final List<CidrRange> ranges = new ArrayList<>();

  public CidrMatcher(List<String> cidrs) {
    if (cidrs != null) {
      for (String cidr : cidrs) {
        ranges.add(parseCidr(cidr.trim()));
      }
    }
  }

  public boolean matches(String ip) {
    if (ip == null || ranges.isEmpty()) {
      return false;
    }
    try {
      InetAddress address = InetAddress.getByName(ip.trim());
      byte[] ipBytes = address.getAddress();
      for (CidrRange range : ranges) {
        if (range.matches(ipBytes)) {
          return true;
        }
      }
      return false;
    } catch (UnknownHostException e) {
      return false;
    }
  }

  public static boolean isValidCidr(String cidr) {
    try {
      parseCidr(cidr);
      return true;
    } catch (Exception e) {
      return false;
    }
  }

  private static CidrRange parseCidr(String cidr) {
    int slashIdx = cidr.indexOf('/');
    if (slashIdx < 0) {
      // Host IP without slash -> exact match /32 or /128
      try {
        InetAddress addr = InetAddress.getByName(cidr);
        int bits = addr.getAddress().length * 8;
        return new CidrRange(addr.getAddress(), bits);
      } catch (UnknownHostException e) {
        throw new IllegalArgumentException("Invalid IP address: " + cidr, e);
      }
    }

    String ipPart = cidr.substring(0, slashIdx);
    String prefixPart = cidr.substring(slashIdx + 1);

    try {
      InetAddress addr = InetAddress.getByName(ipPart);
      int prefixLength = Integer.parseInt(prefixPart);
      int maxBits = addr.getAddress().length * 8;
      if (prefixLength < 0 || prefixLength > maxBits) {
        throw new IllegalArgumentException("Invalid CIDR prefix length: " + prefixLength);
      }
      return new CidrRange(addr.getAddress(), prefixLength);
    } catch (Exception e) {
      throw new IllegalArgumentException("Invalid CIDR notation: " + cidr, e);
    }
  }

  private static class CidrRange {
    private final byte[] networkBytes;
    private final int prefixLength;

    CidrRange(byte[] networkBytes, int prefixLength) {
      this.networkBytes = networkBytes;
      this.prefixLength = prefixLength;
    }

    boolean matches(byte[] ipBytes) {
      if (ipBytes.length != networkBytes.length) {
        return false;
      }

      int fullBytes = prefixLength / 8;
      for (int i = 0; i < fullBytes; i++) {
        if (ipBytes[i] != networkBytes[i]) {
          return false;
        }
      }

      int remainingBits = prefixLength % 8;
      if (remainingBits > 0) {
        int mask = (0xFF << (8 - remainingBits)) & 0xFF;
        return (ipBytes[fullBytes] & mask) == (networkBytes[fullBytes] & mask);
      }

      return true;
    }
  }
}
