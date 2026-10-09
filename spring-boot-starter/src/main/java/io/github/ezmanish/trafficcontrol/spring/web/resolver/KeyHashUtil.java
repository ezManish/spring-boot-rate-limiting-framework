package io.github.ezmanish.trafficcontrol.spring.web.resolver;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Hashes client identity strings to avoid leaking raw identifiers/PII into Redis keys or logs. */
public final class KeyHashUtil {

  private KeyHashUtil() {}

  public static String hash(String rawKey) {
    if (rawKey == null) {
      return "anon";
    }
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] encodedhash = digest.digest(rawKey.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      // Truncated to 16 hex characters (64 bits) for compact, cluster-friendly hash tags
      for (int i = 0; i < Math.min(8, encodedhash.length); i++) {
        String hex = Integer.toHexString(0xff & encodedhash[i]);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 not available", e);
    }
  }
}
