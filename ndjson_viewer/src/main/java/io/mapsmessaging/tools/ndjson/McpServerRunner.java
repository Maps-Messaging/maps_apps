/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Pattern;

final class McpServerRunner {

  static final int DEFAULT_MAX_ROWS = 200;
  static final int MAX_ROWS = 5000;

  private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();
  private static final Pattern READ_ONLY_PREFIX = Pattern.compile(
      "^(SELECT|WITH|SHOW|DESCRIBE|DESC|EXPLAIN|SUMMARIZE|VALUES)\\b",
      Pattern.CASE_INSENSITIVE);
  private static final Pattern MUTATING_KEYWORD = Pattern.compile(
      "\\b(ALTER|ATTACH|CALL|CHECKPOINT|COPY|CREATE|DELETE|DETACH|DROP|EXPORT|IMPORT|INSERT|"
          + "INSTALL|LOAD|MERGE|PRAGMA|RESET|SET|TRUNCATE|UPDATE|VACUUM)\\b",
      Pattern.CASE_INSENSITIVE);

  private final DuckDbLogDatabase database;

  McpServerRunner(DuckDbLogDatabase database) {
    this.database = database;
  }

  void run() throws Exception {
    prepareForMcp();

    CountDownLatch inputClosed = new CountDownLatch(1);
    InputStream input = new EofSignalInputStream(System.in, inputClosed);
    StdioServerTransportProvider transportProvider =
        new StdioServerTransportProvider(McpJsonDefaults.getMapper(), input, System.out);

    McpSyncServer server = McpServer.sync(transportProvider)
        .serverInfo("maps-ndjson-viewer", "1.0.0")
        .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build())
        .instructions(
            "Read-only access to MAPS NDJSON data loaded into DuckDB. "
                + "Use list_relations and describe_relation before query_sql when the schema is unknown.")
        .toolCall(queryTool(), (exchange, request) -> handleQuery(request.arguments()))
        .toolCall(listTopicsTool(), (exchange, request) -> handleListTopics(request.arguments()))
        .toolCall(
            describeRelationTool(),
            (exchange, request) -> handleDescribeRelation(request.arguments()))
        .toolCall(listRelationsTool(), (exchange, request) -> handleListRelations())
        .build();

    Runtime.getRuntime().addShutdownHook(
        new Thread(server::close, "maps-ndjson-viewer-mcp-shutdown"));
    System.err.println("MCP server started on stdio. Press Ctrl-C to stop.");

    try {
      inputClosed.await();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      server.close();
    }
  }

  String executeReadOnlyQuery(String sql, int maxRows) throws SQLException {
    if (!isReadOnlySql(sql)) {
      throw new IllegalArgumentException("MCP query_sql only accepts read-only SQL");
    }
    if (maxRows < 1 || maxRows > MAX_ROWS) {
      throw new IllegalArgumentException("max_rows must be between 1 and " + MAX_ROWS);
    }

    StringWriter output = new StringWriter();
    long rowCount;
    try (DuckDbLogDatabase.Query query = database.query(sql, maxRows)) {
      if (!query.hasResultSet()) {
        throw new IllegalArgumentException(
            "MCP query_sql requires a statement that returns rows");
      }
      rowCount = new QueryResultPrinter()
          .print(query.resultSet(), QueryOutputFormat.NDJSON, new PrintWriter(output));
    }

    JsonArray rows = new JsonArray();
    for (String line : output.toString().split("\\R")) {
      if (!line.isBlank()) {
        rows.add(JsonParser.parseString(line));
      }
    }

    JsonObject result = new JsonObject();
    result.addProperty("row_count", rowCount);
    result.add("rows", rows);
    return GSON.toJson(result);
  }

  String describeRelation(String relation) throws SQLException {
    List<String> columns = database.columns(relation);
    JsonArray values = new JsonArray();
    columns.forEach(values::add);

    JsonObject result = new JsonObject();
    result.addProperty("relation", relation);
    result.add("columns", values);
    return GSON.toJson(result);
  }

  static boolean isReadOnlySql(String sql) {
    if (sql == null) {
      return false;
    }

    String normalized = stripSqlLiteralsAndComments(sql).trim().toUpperCase(Locale.ROOT);
    return READ_ONLY_PREFIX.matcher(normalized).find()
        && !MUTATING_KEYWORD.matcher(normalized).find();
  }

  private void prepareForMcp() throws SQLException {
    try (DuckDbLogDatabase.Query ignored =
             database.query("SET enable_external_access = false")) {
      // Prevent MCP queries from reading arbitrary local files or network resources.
    }
  }

  private McpSchema.CallToolResult handleQuery(Map<String, Object> arguments) {
    try {
      Map<String, Object> values = arguments == null ? Map.of() : arguments;
      String sql = requiredString(values, "sql");
      int maxRows = integer(values, "max_rows", DEFAULT_MAX_ROWS);
      return success(executeReadOnlyQuery(sql, maxRows));
    } catch (Exception exception) {
      return error(exception.getMessage());
    }
  }

  private McpSchema.CallToolResult handleListTopics(Map<String, Object> arguments) {
    try {
      Map<String, Object> values = arguments == null ? Map.of() : arguments;
      boolean mavlinkOnly = bool(values, "mavlink_only", false);
      String sql =
          mavlinkOnly ? DuckDbLogDatabase.MAVLINK_TOPICS_SQL : DuckDbLogDatabase.TOPICS_SQL;
      return success(executeReadOnlyQuery(sql, MAX_ROWS));
    } catch (Exception exception) {
      return error(exception.getMessage());
    }
  }

  private McpSchema.CallToolResult handleDescribeRelation(Map<String, Object> arguments) {
    try {
      Map<String, Object> values = arguments == null ? Map.of() : arguments;
      return success(describeRelation(requiredString(values, "relation")));
    } catch (Exception exception) {
      return error(exception.getMessage());
    }
  }

  private McpSchema.CallToolResult handleListRelations() {
    try {
      return success(executeReadOnlyQuery(
          "SELECT table_schema, table_name, table_type "
              + "FROM information_schema.tables "
              + "WHERE table_schema NOT IN ('information_schema', 'pg_catalog') "
              + "ORDER BY table_schema, table_name",
          MAX_ROWS));
    } catch (Exception exception) {
      return error(exception.getMessage());
    }
  }

  private McpSchema.Tool queryTool() {
    Map<String, Object> schema = Map.of(
        "type", "object",
        "properties", Map.of(
            "sql", Map.of(
                "type", "string",
                "description", "Read-only DuckDB SQL to execute"),
            "max_rows", Map.of(
                "type", "integer",
                "minimum", 1,
                "maximum", MAX_ROWS,
                "default", DEFAULT_MAX_ROWS,
                "description", "Maximum number of rows returned")),
        "required", List.of("sql"),
        "additionalProperties", false);
    return tool(
        "query_sql",
        "Execute read-only DuckDB SQL against the loaded MAPS log data and return JSON rows.",
        schema);
  }

  private McpSchema.Tool listTopicsTool() {
    Map<String, Object> schema = Map.of(
        "type", "object",
        "properties", Map.of(
            "mavlink_only", Map.of(
                "type", "boolean",
                "default", false,
                "description", "When true, list only MAVLink topics")),
        "additionalProperties", false);
    return tool("list_topics", "List topics and record counts.", schema);
  }

  private McpSchema.Tool describeRelationTool() {
    Map<String, Object> schema = Map.of(
        "type", "object",
        "properties", Map.of(
            "relation", Map.of(
                "type", "string",
                "description", "DuckDB relation name to describe")),
        "required", List.of("relation"),
        "additionalProperties", false);
    return tool(
        "describe_relation",
        "Describe the columns and DuckDB types of a relation.",
        schema);
  }

  private McpSchema.Tool listRelationsTool() {
    return tool(
        "list_relations",
        "List available DuckDB tables and views.",
        Map.of("type", "object", "additionalProperties", false));
  }

  private McpSchema.Tool tool(
      String name,
      String description,
      Map<String, Object> inputSchema) {
    McpSchema.ToolAnnotations annotations = McpSchema.ToolAnnotations.builder()
        .readOnlyHint(true)
        .destructiveHint(false)
        .idempotentHint(true)
        .openWorldHint(false)
        .build();
    return McpSchema.Tool.builder(name, inputSchema)
        .description(description)
        .annotations(annotations)
        .build();
  }

  private McpSchema.CallToolResult success(String text) {
    return McpSchema.CallToolResult.builder()
        .addTextContent(text)
        .isError(false)
        .build();
  }

  private McpSchema.CallToolResult error(String message) {
    String text = message == null || message.isBlank() ? "MCP tool execution failed" : message;
    return McpSchema.CallToolResult.builder()
        .addTextContent(text)
        .isError(true)
        .build();
  }

  private String requiredString(Map<String, Object> arguments, String name) {
    Object value = arguments.get(name);
    if (value instanceof String stringValue && !stringValue.isBlank()) {
      return stringValue;
    }
    throw new IllegalArgumentException("Missing or empty argument: " + name);
  }

  private int integer(Map<String, Object> arguments, String name, int defaultValue) {
    Object value = arguments.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Number number) {
      return number.intValue();
    }
    throw new IllegalArgumentException("Argument " + name + " must be an integer");
  }

  private boolean bool(Map<String, Object> arguments, String name, boolean defaultValue) {
    Object value = arguments.get(name);
    if (value == null) {
      return defaultValue;
    }
    if (value instanceof Boolean booleanValue) {
      return booleanValue;
    }
    throw new IllegalArgumentException("Argument " + name + " must be a boolean");
  }

  private static String stripSqlLiteralsAndComments(String sql) {
    StringBuilder stripped = new StringBuilder(sql.length());
    boolean singleQuoted = false;
    boolean doubleQuoted = false;
    boolean lineComment = false;
    boolean blockComment = false;

    for (int index = 0; index < sql.length(); index++) {
      char current = sql.charAt(index);
      char next = index + 1 < sql.length() ? sql.charAt(index + 1) : '\0';

      if (lineComment) {
        if (current == '\n' || current == '\r') {
          lineComment = false;
          stripped.append(current);
        } else {
          stripped.append(' ');
        }
        continue;
      }

      if (blockComment) {
        if (current == '*' && next == '/') {
          stripped.append("  ");
          index++;
          blockComment = false;
        } else {
          stripped.append(' ');
        }
        continue;
      }

      if (singleQuoted) {
        stripped.append(' ');
        if (current == '\'' && next == '\'') {
          stripped.append(' ');
          index++;
        } else if (current == '\'') {
          singleQuoted = false;
        }
        continue;
      }

      if (doubleQuoted) {
        stripped.append(' ');
        if (current == '"' && next == '"') {
          stripped.append(' ');
          index++;
        } else if (current == '"') {
          doubleQuoted = false;
        }
        continue;
      }

      if (current == '-' && next == '-') {
        stripped.append("  ");
        index++;
        lineComment = true;
      } else if (current == '/' && next == '*') {
        stripped.append("  ");
        index++;
        blockComment = true;
      } else if (current == '\'') {
        stripped.append(' ');
        singleQuoted = true;
      } else if (current == '"') {
        stripped.append(' ');
        doubleQuoted = true;
      } else {
        stripped.append(current);
      }
    }

    return stripped.toString();
  }

  private static final class EofSignalInputStream extends FilterInputStream {

    private final CountDownLatch inputClosed;

    private EofSignalInputStream(InputStream input, CountDownLatch inputClosed) {
      super(input);
      this.inputClosed = inputClosed;
    }

    @Override
    public int read() throws IOException {
      int value = super.read();
      if (value == -1) {
        inputClosed.countDown();
      }
      return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
      int count = super.read(buffer, offset, length);
      if (count == -1) {
        inputClosed.countDown();
      }
      return count;
    }
  }
}
