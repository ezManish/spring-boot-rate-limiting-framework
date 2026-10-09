package io.github.ezmanish.trafficcontrol.store.redis;

import io.lettuce.core.RedisCommandExecutionException;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages Lua script loading, SHA caching, EVALSHA execution, and NOSCRIPT reload/retry (TC-172).
 */
public class RedisScriptManager {

  private static final Logger log = LoggerFactory.getLogger(RedisScriptManager.class);

  private final String decideScript;
  private final String leaseExtendScript;
  private final AtomicReference<String> decideSha = new AtomicReference<>();
  private final AtomicReference<String> leaseExtendSha = new AtomicReference<>();

  public RedisScriptManager() {
    this.decideScript = loadResource("/lua/tc_decide.lua");
    this.leaseExtendScript = loadResource("/lua/tc_lease_extend.lua");
  }

  public List<Long> evalDecide(
      RedisCommands<String, String> commands, String[] keys, String[] args) {
    return executeWithRetry(commands, decideSha, decideScript, keys, args);
  }

  public Long evalLeaseExtend(
      RedisCommands<String, String> commands, String[] keys, String[] args) {
    String sha = ensureSha(commands, leaseExtendSha, leaseExtendScript);
    try {
      return commands.evalsha(sha, ScriptOutputType.INTEGER, keys, args);
    } catch (RedisCommandExecutionException e) {
      if (isNoScript(e)) {
        log.warn("NOSCRIPT received for lease_extend; reloading script and retrying once");
        sha = commands.scriptLoad(leaseExtendScript);
        leaseExtendSha.set(sha);
        return commands.evalsha(sha, ScriptOutputType.INTEGER, keys, args);
      }
      throw e;
    }
  }

  @SuppressWarnings("unchecked")
  private List<Long> executeWithRetry(
      RedisCommands<String, String> commands,
      AtomicReference<String> shaRef,
      String scriptText,
      String[] keys,
      String[] args) {
    String sha = ensureSha(commands, shaRef, scriptText);
    try {
      return commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
    } catch (RedisCommandExecutionException e) {
      if (isNoScript(e)) {
        log.warn("NOSCRIPT received for tc_decide; reloading script and retrying once (TC-172)");
        sha = commands.scriptLoad(scriptText);
        shaRef.set(sha);
        return commands.evalsha(sha, ScriptOutputType.MULTI, keys, args);
      }
      throw e;
    }
  }

  private String ensureSha(
      RedisCommands<String, String> commands, AtomicReference<String> shaRef, String scriptText) {
    String sha = shaRef.get();
    if (sha == null) {
      sha = commands.scriptLoad(scriptText);
      shaRef.set(sha);
    }
    return sha;
  }

  private boolean isNoScript(RedisCommandExecutionException e) {
    return e.getMessage() != null && e.getMessage().toUpperCase().contains("NOSCRIPT");
  }

  private String loadResource(String path) {
    try (InputStream is = getClass().getResourceAsStream(path)) {
      if (is == null) {
        throw new IllegalStateException("Could not find script resource: " + path);
      }
      try (BufferedReader reader =
          new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
        return reader.lines().collect(Collectors.joining("\n"));
      }
    } catch (Exception e) {
      throw new RuntimeException("Failed to load Redis Lua script: " + path, e);
    }
  }
}
