package io.mapsmessaging.tools.mavlink;

import io.mapsmessaging.tools.mavlink.replay.MavlinkPacketInfo;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MavlinkFilterTest {

  private final MavlinkPacketInfo packetInfo = new MavlinkPacketInfo(2, 0, 1, 10, 1, 33, false, 12, 0);

  @Test
  void emptyFilterMatchesAnyPacket() {
    assertTrue(new MavlinkFilter(Set.of(), Set.of(), Set.of()).matches(packetInfo));
  }

  @Test
  void appliesEachConfiguredIdentifier() {
    assertTrue(new MavlinkFilter(Set.of(10), Set.of(1), Set.of(33)).matches(packetInfo));
    assertFalse(new MavlinkFilter(Set.of(11), Set.of(), Set.of()).matches(packetInfo));
    assertFalse(new MavlinkFilter(Set.of(), Set.of(2), Set.of()).matches(packetInfo));
    assertFalse(new MavlinkFilter(Set.of(), Set.of(), Set.of(24)).matches(packetInfo));
  }
}
