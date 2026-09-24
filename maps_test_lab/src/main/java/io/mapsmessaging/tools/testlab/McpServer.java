package io.mapsmessaging.tools.testlab;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class McpServer implements AutoCloseable {

  private static final String PROTOCOL_VERSION = "2025-06-18";

  private final HttpServer server;
  private final InstanceManager manager;
  private final ExecutorService executor;
  private final Gson gson = new Gson();

  public McpServer(LabConfig config, InstanceManager manager) throws IOException {
    this.manager = manager;
    server = HttpServer.create(new InetSocketAddress(config.bindAddress(), config.port()), 0);
    server.createContext("/mcp", this::handle);
    executor = Executors.newVirtualThreadPerTaskExecutor();
    server.setExecutor(executor);
  }

  public void start() {
    server.start();
  }

  public int port() {
    return server.getAddress().getPort();
  }

  @Override
  public void close() {
    server.stop(0);
    executor.close();
  }

  private void handle(HttpExchange exchange) throws IOException {
    try {
      if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
        send(exchange, 405, error(null, -32600, "Only POST is supported"));
        return;
      }

      String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      JsonObject request = JsonParser.parseString(body).getAsJsonObject();
      JsonElement id = request.get("id");
      String method = string(request, "method", null);

      if ("notifications/initialized".equals(method)) {
        exchange.sendResponseHeaders(202, -1);
        return;
      }

      JsonObject response =
          switch (method == null ? "" : method) {
            case "initialize" -> initialize(id, exchange);
            case "ping" -> result(id, new JsonObject());
            case "tools/list" -> result(id, toolList());
            case "tools/call" -> callTool(id, request.getAsJsonObject("params"));
            default -> error(id, -32601, "Unknown method: " + method);
          };

      send(exchange, 200, response);
    } catch (Exception exception) {
      send(exchange, 200, error(null, -32603, exception.getMessage()));
    } finally {
      exchange.close();
    }
  }

  private JsonObject initialize(JsonElement id, HttpExchange exchange) {
    exchange.getResponseHeaders().set("Mcp-Session-Id", UUID.randomUUID().toString());

    JsonObject value = new JsonObject();
    value.addProperty("protocolVersion", PROTOCOL_VERSION);

    JsonObject capabilities = new JsonObject();
    capabilities.add("tools", new JsonObject());
    value.add("capabilities", capabilities);

    JsonObject serverInfo = new JsonObject();
    serverInfo.addProperty("name", "maps-test-lab");
    serverInfo.addProperty("version", "1.0.0");
    value.add("serverInfo", serverInfo);
    return result(id, value);
  }

  private JsonObject toolList() {
    JsonArray tools = new JsonArray();
    tools.add(
        tool(
            "list_instances",
            "List configured MapsMessaging test instances",
            properties(Map.of())));
    tools.add(tool("start_instance", "Start one configured MapsMessaging instance", stringArgs("name")));
    tools.add(tool("stop_instance", "Stop one managed MapsMessaging instance", stringArgs("name")));
    tools.add(tool("restart_instance", "Restart one managed MapsMessaging instance", stringArgs("name")));
    tools.add(tool("instance_status", "Return process or container status", stringArgs("name")));

    tools.add(
        tool(
            "instance_logs",
            "Return the tail of process or Docker logs",
            properties(
                Map.of(
                    "name", schema("string"),
                    "lines", schema("integer")),
                "name")));

    tools.add(
        tool(
            "list_log_files",
            "List persisted log files available for an instance",
            stringArgs("name")));

    tools.add(
        tool(
            "read_log",
            "Read a bounded chunk of a persisted log file for analysis",
            properties(
                Map.of(
                    "name", schema("string"),
                    "path", schema("string"),
                    "offset", schema("integer"),
                    "bytes", schema("integer")),
                "name",
                "path")));

    tools.add(
        tool(
            "instance_config",
            "Read or list files within an instance config directory",
            properties(
                Map.of(
                    "name", schema("string"),
                    "path", schema("string")),
                "name")));

    tools.add(
        tool(
            "write_instance_config",
            "Create or replace one file within an instance config directory",
            properties(
                Map.of(
                    "name", schema("string"),
                    "path", schema("string"),
                    "content", schema("string"),
                    "encoding", enumSchema("utf8", "base64"),
                    "overwrite", schema("boolean")),
                "name",
                "path",
                "content")));

    tools.add(
        tool(
            "mqtt_publish",
            "Publish a test MQTT message to the broker configured for an instance",
            properties(
                Map.of(
                    "name", schema("string"),
                    "topic", schema("string"),
                    "payload", schema("string"),
                    "qos", schema("integer"),
                    "retain", schema("boolean")),
                "name",
                "topic")));

    tools.add(
        tool(
            "mqtt_subscribe",
            "Wait for one MQTT message from the broker configured for an instance",
            properties(
                Map.of(
                    "name", schema("string"),
                    "topic", schema("string"),
                    "qos", schema("integer"),
                    "timeoutSeconds", schema("integer")),
                "name",
                "topic")));

    tools.add(
        tool(
            "create_evidence",
            "Snapshot config, logs and instance status",
            properties(
                Map.of(
                    "name", schema("string"),
                    "scenario", schema("string")),
                "name")));

    tools.add(
        tool(
            "create_evidence_archive",
            "Create a ZIP bundle containing config, logs and instance status",
            properties(
                Map.of(
                    "name", schema("string"),
                    "scenario", schema("string")),
                "name")));

    JsonObject result = new JsonObject();
    result.add("tools", tools);
    return result;
  }

  private JsonObject callTool(JsonElement id, JsonObject params) {
    if (params == null) {
      return toolError(id, "Missing tool call params");
    }

    String name = string(params, "name", null);
    JsonObject args = params.has("arguments") ? params.getAsJsonObject("arguments") : new JsonObject();

    try {
      Object value =
          switch (name == null ? "" : name) {
            case "list_instances" -> manager.listInstances();
            case "start_instance" -> manager.start(required(args, "name"));
            case "stop_instance" -> manager.stop(required(args, "name"));
            case "restart_instance" -> manager.restart(required(args, "name"));
            case "instance_status" -> manager.status(required(args, "name"));
            case "instance_logs" ->
                manager.logs(required(args, "name"), integer(args, "lines", 200));
            case "list_log_files" -> manager.listLogFiles(required(args, "name"));
            case "read_log" ->
                manager.readLog(
                    required(args, "name"),
                    required(args, "path"),
                    longValue(args, "offset", 0L),
                    integer(args, "bytes", 64 * 1024));
            case "instance_config" ->
                manager.readConfig(required(args, "name"), string(args, "path", "."));
            case "write_instance_config" ->
                manager.writeConfig(
                    required(args, "name"),
                    required(args, "path"),
                    required(args, "content"),
                    string(args, "encoding", "utf8"),
                    bool(args, "overwrite", false));
            case "mqtt_publish" ->
                manager.mqttPublish(
                    required(args, "name"),
                    required(args, "topic"),
                    string(args, "payload", ""),
                    integer(args, "qos", 0),
                    bool(args, "retain", false));
            case "mqtt_subscribe" ->
                manager.mqttSubscribe(
                    required(args, "name"),
                    required(args, "topic"),
                    integer(args, "qos", 0),
                    integer(args, "timeoutSeconds", 10));
            case "create_evidence" -> {
              Path path =
                  manager.createEvidence(
                      required(args, "name"), string(args, "scenario", "manual"));
              yield Map.of("path", path.toString());
            }
            case "create_evidence_archive" ->
                manager.createEvidenceArchive(
                    required(args, "name"), string(args, "scenario", "manual"));
            default -> throw new IllegalArgumentException("Unknown tool: " + name);
          };

      return toolResult(id, gson.toJson(value), false);
    } catch (Exception exception) {
      return toolResult(id, exception.getMessage(), true);
    }
  }

  private JsonObject tool(
      String name, String description, Map<String, Object> schemaValues) {
    JsonObject tool = new JsonObject();
    tool.addProperty("name", name);
    tool.addProperty("description", description);
    tool.add("inputSchema", gson.toJsonTree(schemaValues));
    return tool;
  }

  private Map<String, Object> stringArgs(String... required) {
    Map<String, Object> props = new LinkedHashMap<>();
    for (String value : required) {
      props.put(value, schema("string"));
    }
    return properties(props, required);
  }

  private Map<String, Object> properties(Map<String, Object> props, String... required) {
    Map<String, Object> schema = new LinkedHashMap<>();
    schema.put("type", "object");
    schema.put("properties", props);
    schema.put("required", java.util.List.of(required));
    schema.put("additionalProperties", false);
    return schema;
  }

  private Map<String, Object> schema(String type) {
    return Map.of("type", type);
  }

  private Map<String, Object> enumSchema(String... values) {
    return Map.of("type", "string", "enum", java.util.List.of(values));
  }

  private JsonObject toolResult(JsonElement id, String text, boolean isError) {
    JsonObject result = new JsonObject();
    JsonArray content = new JsonArray();
    JsonObject item = new JsonObject();
    item.addProperty("type", "text");
    item.addProperty("text", text == null ? "" : text);
    content.add(item);
    result.add("content", content);
    result.addProperty("isError", isError);
    return result(id, result);
  }

  private JsonObject result(JsonElement id, JsonElement value) {
    JsonObject response = base(id);
    response.add("result", value);
    return response;
  }

  private JsonObject error(JsonElement id, int code, String message) {
    JsonObject response = base(id);
    JsonObject error = new JsonObject();
    error.addProperty("code", code);
    error.addProperty("message", message == null ? "Unknown error" : message);
    response.add("error", error);
    return response;
  }

  private JsonObject base(JsonElement id) {
    JsonObject response = new JsonObject();
    response.addProperty("jsonrpc", "2.0");
    response.add("id", id == null ? com.google.gson.JsonNull.INSTANCE : id);
    return response;
  }

  private JsonObject toolError(JsonElement id, String message) {
    return toolResult(id, message, true);
  }

  private String required(JsonObject object, String name) {
    String value = string(object, name, null);
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private String string(JsonObject object, String name, String fallback) {
    return object != null && object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsString()
        : fallback;
  }

  private int integer(JsonObject object, String name, int fallback) {
    return object != null && object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsInt()
        : fallback;
  }

  private long longValue(JsonObject object, String name, long fallback) {
    return object != null && object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsLong()
        : fallback;
  }

  private boolean bool(JsonObject object, String name, boolean fallback) {
    return object != null && object.has(name) && !object.get(name).isJsonNull()
        ? object.get(name).getAsBoolean()
        : fallback;
  }

  private void send(HttpExchange exchange, int status, JsonObject response) throws IOException {
    byte[] bytes = gson.toJson(response).getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }
}
