package io.mapsmessaging.tools.testlab;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;

public final class MapsTestLabMain {

  private MapsTestLabMain() {}

  public static void main(String[] args) throws Exception {
    Path configPath = parseConfig(args);
    LabConfig config = LabConfig.load(configPath);

    InstanceManager manager = new InstanceManager(config);
    McpServer server = new McpServer(config, manager);

    Runtime.getRuntime().addShutdownHook(new Thread(() -> {
      server.close();
      manager.close();
    }, "maps-test-lab-shutdown"));

    server.start();
    System.err.println(
        "MAPS Test Lab MCP server listening on http://"
            + config.bindAddress()
            + ":"
            + server.port()
            + "/mcp");
    new CountDownLatch(1).await();
  }

  private static Path parseConfig(String[] args) {
    if (args.length == 2 && "--config".equals(args[0])) {
      return Path.of(args[1]);
    }

    if (args.length == 1 && !args[0].startsWith("-")) {
      return Path.of(args[0]);
    }

    throw new IllegalArgumentException(
        "Usage: java -jar maps_test_lab.jar --config <lab.json>");
  }
}
