package io.mapsmessaging.tools.testlab;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpServerTest {

  @TempDir Path tempDir;

  @Test
  void initializesAndListsTools() throws Exception {
    int port;
    try (ServerSocket socket = new ServerSocket(0)) {
      port = socket.getLocalPort();
    }

    LabConfig config =
        new LabConfig(
            tempDir,
            "127.0.0.1",
            port,
            "mosquitto_pub",
            "mosquitto_sub",
            Map.of(
                "alpha",
                new LabConfig.InstanceConfig(
                    List.of(
                        javaBinary(),
                        "-cp",
                        System.getProperty("java.class.path"),
                        TestSleeper.class.getName()),
                    Map.of(),
                    "127.0.0.1",
                    1883)));

    try (InstanceManager manager = new InstanceManager(config);
        McpServer server = new McpServer(config, manager)) {
      server.start();

      HttpResponse<String> initialize =
          post(
              port,
              """
              {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
              """);

      assertEquals(200, initialize.statusCode());
      assertTrue(initialize.headers().firstValue("Mcp-Session-Id").isPresent());
      assertTrue(initialize.body().contains("\"protocolVersion\":\"2025-06-18\""));

      HttpResponse<String> tools =
          post(
              port,
              """
              {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
              """);

      assertEquals(200, tools.statusCode());
      assertTrue(tools.body().contains("\"start_instance\""));
      assertTrue(tools.body().contains("\"mqtt_publish\""));
      assertTrue(tools.body().contains("\"create_evidence\""));
      assertTrue(tools.body().contains("\"write_instance_config\""));
      assertTrue(tools.body().contains("\"list_log_files\""));
      assertTrue(tools.body().contains("\"read_log\""));
      assertTrue(tools.body().contains("\"create_evidence_archive\""));

      HttpResponse<String> status =
          post(
              port,
              """
              {
                "jsonrpc":"2.0",
                "id":3,
                "method":"tools/call",
                "params":{"name":"instance_status","arguments":{"name":"alpha"}}
              }
              """);

      assertEquals(200, status.statusCode());
      assertTrue(status.body().contains("\"isError\":false"));
      assertTrue(status.body().contains("alpha"));
    }
  }

  private HttpResponse<String> post(int port, String body) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/mcp"))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
  }

  private String javaBinary() {
    return Path.of(
            System.getProperty("java.home"),
            "bin",
            System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java")
        .toString();
  }
}
