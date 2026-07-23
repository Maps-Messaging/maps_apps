package io.mapsmessaging.tools.mavlink;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TimeOffsetConverterTest {

  @Test
  void parsesSupportedOffsets() {
    assertEquals(Duration.ofSeconds(90), TimeOffsetConverter.parse("90"));
    assertEquals(Duration.ofSeconds(90), TimeOffsetConverter.parse("01:30"));
    assertEquals(Duration.ofSeconds(90), TimeOffsetConverter.parse("00:01:30"));
    assertEquals(Duration.ofMillis(250), TimeOffsetConverter.parse("250ms"));
    assertEquals(Duration.ofMinutes(3), TimeOffsetConverter.parse("3m"));
  }

  @Test
  void rejectsInvalidClockComponents() {
    assertThrows(IllegalArgumentException.class, () -> TimeOffsetConverter.parse("01:75"));
  }
}
