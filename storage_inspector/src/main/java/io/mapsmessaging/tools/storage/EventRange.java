/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

/** Inclusive event IDs; absent bounds leave that side unrestricted. */
record EventRange(Long start, Long end) {
  static final EventRange ALL = new EventRange(null, null);

  EventRange {
    if (start != null && start < 0 || end != null && end < 0) {
      throw new IllegalArgumentException("Event IDs must be non-negative");
    }
    if (start != null && end != null && start > end) {
      throw new IllegalArgumentException("--start-event-id must not exceed --end-event-id");
    }
  }

  boolean filtered() { return start != null || end != null; }
  long firstSlot(long partitionStart) { return start == null || start <= partitionStart ? 0 : start - partitionStart; }
  long lastSlot(long partitionStart, long slots) {
    if (end != null && end < partitionStart) return -1;
    return end == null ? slots - 1 : Math.min(slots - 1, end - partitionStart);
  }
  boolean overlaps(long first, long last) {
    return (start == null || start <= last) && (end == null || end >= first);
  }
}
