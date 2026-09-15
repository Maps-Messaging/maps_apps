package io.mapsmessaging.tools.valuelogger;

public class MapsValueLoggerMain {

  public static void main(String[] args) {
    MapsValueLogger logger = null;
    int exitCode = 0;

    try {
      MapsValueLoggerArguments arguments = MapsValueLoggerArguments.parse(args);

      logger = new MapsValueLogger(arguments);
      MapsValueLogger shutdownLogger = logger;
      Runtime.getRuntime().addShutdownHook(new Thread(shutdownLogger::stop));

      logger.start();
      logger.await();
    } catch (IllegalArgumentException exception) {
      System.err.println(exception.getMessage());
      MapsValueLoggerArguments.printUsage();
      exitCode = 1;
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (Exception exception) {
      exception.printStackTrace(System.err);
      exitCode = 2;
    } finally {
      if (logger != null) {
        logger.stop();
      }
    }

    if (exitCode != 0) {
      System.exit(exitCode);
    }
  }
}
