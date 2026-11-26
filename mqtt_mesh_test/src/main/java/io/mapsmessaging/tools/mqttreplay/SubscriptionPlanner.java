package io.mapsmessaging.tools.mqttreplay;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.eclipse.paho.client.mqttv3.MqttTopic;

/** Deterministic disjoint prefix partition of the recorded topic space. */
final class SubscriptionPlanner {
  record Filter(String value, long events, List<String> topics) {
  }

  record Assignment(List<Filter> filters, long events) {
    List<String> values() {
      return filters.stream().map(Filter::value).toList();
    }

    int topicCount() {
      return filters.stream().mapToInt(filter -> filter.topics().size()).sum();
    }
  }

  static final class Node {
    final String path;
    long direct;
    long aggregate;
    final Map<String, Node> children = new TreeMap<>();

    Node(String path) {
      this.path = path;
    }

    List<String> topics() {
      List<String> topics = new ArrayList<>();
      if (direct > 0) {
        topics.add(path);
      }
      for (Node child : children.values()) {
        topics.addAll(child.topics());
      }
      return topics;
    }
  }

  static Node hierarchy(Map<String, Long> counts) {
    Node root = new Node(null);
    counts.forEach((topic, count) -> {
      MqttTopic.validate(topic, false);
      if (count <= 0) {
        throw new IllegalArgumentException("Invalid topic count: " + topic);
      }
      Node node = root;
      node.aggregate += count;
      for (String level : topic.split("/", -1)) {
        String path = node.path == null ? level : node.path + "/" + level;
        node = node.children.computeIfAbsent(level, ignored -> new Node(path));
        node.aggregate += count;
      }
      node.direct += count;
    });
    return root;
  }

  static boolean matches(String filter, String topic) {
    MqttTopic.validate(filter, true);
    if (topic.startsWith("$") && (filter.startsWith("#") || filter.startsWith("+"))) {
      return false;
    }
    String[] levels = filter.split("/", -1);
    String[] actual = topic.split("/", -1);
    int i = 0;
    for (String level : levels) {
      if (level.equals("#")) {
        return true;
      }
      if (i == actual.length || (!level.equals("+") && !level.equals(actual[i]))) {
        return false;
      }
      i++;
    }
    return i == actual.length;
  }

  static List<Assignment> plan(Map<String, Long> counts, int sessions, String allowed) {
    if (sessions < 1) {
      throw new IllegalArgumentException("Subscriber sessions must be positive");
    }
    MqttTopic.validate(allowed, true);
    Node root = hierarchy(counts);
    // The default filter is automatic for a pool; explicit narrower filters remain constraints.
    if (!allowed.equals("#")) {
      for (String topic : counts.keySet()) {
        if (!matches(allowed, topic)) {
          throw new IllegalArgumentException("Observation filter does not cover recorded topic: " + topic);
        }
      }
    }
    List<Filter> candidates = new ArrayList<>();
    if (sessions == 1 && (!allowed.equals("#") || counts.keySet().stream().noneMatch(topic -> topic.startsWith("$")))) {
      candidates.add(new Filter(allowed, root.aggregate, root.topics()));
    } else {
      double target = (double) root.aggregate / sessions;
      for (Node node : root.children.values()) {
        split(node, target, allowed, candidates);
      }
    }
    candidates.sort(Comparator.comparingLong(Filter::events).reversed().thenComparing(Filter::value));
    List<List<Filter>> allocations = new ArrayList<>();
    long[] loads = new long[sessions];
    for (int i = 0; i < sessions; i++) {
      allocations.add(new ArrayList<>());
    }
    for (Filter filter : candidates) {
      int index = 0;
      for (int i = 1; i < sessions; i++) {
        if (loads[i] < loads[index]) {
          index = i;
        }
      }
      allocations.get(index).add(filter);
      loads[index] += filter.events();
    }
    Map<String, Integer> coverage = new HashMap<>();
    // Generated filters are structurally disjoint: exact terminal or whole unsplit subtree.
    for (Filter filter : candidates) {
      MqttTopic.validate(filter.value(), true);
      for (String topic : filter.topics()) {
        if (!matches(filter.value(), topic)) {
          throw new IllegalStateException("Invalid subscription coverage: " + topic);
        }
        coverage.merge(topic, 1, Integer::sum);
      }
    }
    for (String topic : counts.keySet()) {
      if (coverage.getOrDefault(topic, 0) != 1) {
        throw new IllegalStateException("Missing or overlapping subscription: " + topic);
      }
    }
    List<Assignment> result = new ArrayList<>();
    for (int i = 0; i < sessions; i++) {
      result.add(new Assignment(List.copyOf(allocations.get(i)), loads[i]));
    }
    return List.copyOf(result);
  }

  private static void split(Node node, double target, String allowed, List<Filter> result) {
    String wildcard = node.path + "/#";
    // Avoid widening explicit filters. A leading empty level is represented by /#.
    boolean canGroup = allowed.equals("#") || allowed.equals(wildcard)
        || (allowed.endsWith("/#") && wildcard.startsWith(allowed.substring(0, allowed.length() - 1)));
    if (!node.children.isEmpty() && node.aggregate <= target && canGroup) {
      result.add(new Filter(wildcard, node.aggregate, node.topics()));
      return;
    }
    if (node.direct > 0) {
      result.add(new Filter(node.path, node.direct, List.of(node.path)));
    }
    for (Node child : node.children.values()) {
      split(child, target, allowed, result);
    }
  }
}
