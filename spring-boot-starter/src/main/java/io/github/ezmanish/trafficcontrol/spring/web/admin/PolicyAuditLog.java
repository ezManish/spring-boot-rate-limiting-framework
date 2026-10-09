package io.github.ezmanish.trafficcontrol.spring.web.admin;

import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicySnapshot;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Thread-safe audit log store keeping track of policy modifications and snapshot history for
 * rollback. Keeps the last 20 policy snapshots for rollback (05_API_SPECIFICATION.md §5).
 */
public class PolicyAuditLog {

  private static final int MAX_SNAPSHOT_HISTORY = 20;
  private static final int MAX_AUDIT_ENTRIES = 10_000;

  private final Deque<PolicyAuditEntry> entries = new ConcurrentLinkedDeque<>();
  private final Map<Long, PolicySnapshot> snapshotHistory = new ConcurrentHashMap<>();

  public void record(PolicyAuditEntry entry) {
    if (entry != null) {
      entries.addFirst(entry);
      while (entries.size() > MAX_AUDIT_ENTRIES) {
        entries.removeLast();
      }
    }
  }

  public void retainSnapshot(PolicySnapshot snapshot) {
    if (snapshot != null) {
      snapshotHistory.put(snapshot.version(), snapshot);
      if (snapshotHistory.size() > MAX_SNAPSHOT_HISTORY) {
        snapshotHistory.keySet().stream().min(Long::compareTo).ifPresent(snapshotHistory::remove);
      }
    }
  }

  public Optional<PolicySnapshot> getRetainedSnapshot(long version) {
    return Optional.ofNullable(snapshotHistory.get(version));
  }

  public List<PolicyAuditEntry> getEntries(int limit) {
    int safeLimit = Math.min(Math.max(1, limit), 100);
    List<PolicyAuditEntry> result = new ArrayList<>(safeLimit);
    for (PolicyAuditEntry entry : entries) {
      if (result.size() >= safeLimit) {
        break;
      }
      result.add(entry);
    }
    return result;
  }
}
