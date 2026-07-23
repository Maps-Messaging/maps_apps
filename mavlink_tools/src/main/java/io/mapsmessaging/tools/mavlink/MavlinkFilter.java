package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketInfo;

import java.util.Set;

public record MavlinkFilter(Set<Integer> systemIds, Set<Integer> componentIds, Set<Integer> messageIds) {

  public MavlinkFilter {
    systemIds = Set.copyOf(systemIds);
    componentIds = Set.copyOf(componentIds);
    messageIds = Set.copyOf(messageIds);
  }

  public boolean isActive() {
    return !systemIds.isEmpty() || !componentIds.isEmpty() || !messageIds.isEmpty();
  }

  public boolean matches(MavlinkPacketInfo packetInfo) {
    return matches(systemIds, packetInfo.systemId())
        && matches(componentIds, packetInfo.componentId())
        && matches(messageIds, packetInfo.messageId());
  }

  private boolean matches(Set<Integer> values, int value) {
    return values.isEmpty() || values.contains(value);
  }
}
