package io.mapsmessaging.tools.mavlink.replay;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ReplayFormatTest {

  @Test
  void parsesAliases() {
    assertEquals(ReplayFormat.AUTO, ReplayFormat.parse(null));
    assertEquals(ReplayFormat.LEGACY_DAT, ReplayFormat.parse("legacy-dat"));
    assertEquals(ReplayFormat.LEGACY_DAT, ReplayFormat.parse("csv"));
    assertEquals(ReplayFormat.RAW, ReplayFormat.parse("rlog"));
  }
}
