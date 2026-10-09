package io.github.ezmanish.trafficcontrol.spring.web.policy.update;

import io.github.ezmanish.trafficcontrol.spring.web.policy.PolicySnapshot;

/** SPI for broadcasting policy updates across distributed instances. */
public interface PolicyUpdateBroadcaster {

  /** Publishes a new policy snapshot to all cluster nodes. */
  void broadcastUpdate(PolicySnapshot snapshot, String rawJson);
}
