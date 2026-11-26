package io.mapsmessaging.tools.mqttreplay;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SubscriptionPlannerTest {
  @Test
  void hierarchyPreservesEmptyLevelsAndTerminals() {
    SubscriptionPlanner.Node root = SubscriptionPlanner.hierarchy(Map.of("/a", 3L, "/a/", 2L, "//a", 4L, "$SYS/x", 1L));
    assertEquals(10L, root.aggregate);
    assertEquals(3L, root.children.get("").children.get("a").direct);
    assertEquals(5L, root.children.get("").children.get("a").aggregate);
  }

  @Test
  void mqttMatching() {
    assertTrue(SubscriptionPlanner.matches("a/#", "a"));
    assertTrue(SubscriptionPlanner.matches("/+/x", "//x"));
    assertFalse(SubscriptionPlanner.matches("#", "$SYS/x"));
    assertTrue(SubscriptionPlanner.matches("$SYS/#", "$SYS/x"));
    assertThrows(IllegalArgumentException.class, () -> SubscriptionPlanner.matches("a/#/b", "a/b"));
  }

  @Test
  void partitionIsDeterministicAndDisjoint() {
    Map<String, Long> counts = Map.of("/a", 3L, "/a/x", 7L, "/b/x", 10L, "$SYS/x", 10L);
    var plan = SubscriptionPlanner.plan(counts, 3, "#");
    assertEquals(plan, SubscriptionPlanner.plan(counts, 3, "#"));
    assertEquals(List.of(10L, 10L, 10L), plan.stream().map(SubscriptionPlanner.Assignment::events).toList());
    for (String topic : counts.keySet()) {
      assertEquals(1L, plan.stream().flatMap(item -> item.values().stream())
          .filter(filter -> SubscriptionPlanner.matches(filter, topic)).count());
    }
  }

  @Test
  void dominantTopicCannotSplit() {
    var plan = SubscriptionPlanner.plan(Map.of("a", 100L, "b", 1L), 3, "#");
    assertEquals(100L, plan.get(0).events());
    assertEquals(1, plan.get(0).topicCount());
  }

  @Test
  void missingCoverageRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> SubscriptionPlanner.plan(Map.of("b", 1L), 2, "a/#"));
  }

  @Test
  void singleSubscriberDefault() {
    assertEquals(List.of("#"), SubscriptionPlanner.plan(Map.of("a", 1L), 1, "#").get(0).values());
  }

  @Test
  void partitionedAccountingDoesNotDuplicateExpectations() {
    ReplayValidationEngine engine = new ReplayValidationEngine(List.of("a", "b", "c", "d"));
    byte[] payload = {1};
    engine.published("x", payload, List.of("a", "c"));
    engine.received("a", "x", payload);
    engine.received("c", "x", payload);
    assertEquals(1, engine.snapshot(Map.of()).published());
    assertTrue(engine.snapshot(Map.of()).successful());
    engine.received("b", "x", payload);
    assertFalse(engine.snapshot(Map.of()).successful());
  }
}
