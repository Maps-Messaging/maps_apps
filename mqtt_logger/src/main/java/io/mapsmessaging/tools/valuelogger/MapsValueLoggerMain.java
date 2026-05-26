package io.mapsmessaging.tools.valuelogger;

public class MapsValueLoggerMain {

  public static void main(String[] args) {
    try {
      MapsValueLoggerArguments arguments = MapsValueLoggerArguments.parse(args);

      MapsValueLogger logger = new MapsValueLogger(arguments);
      logger.start();

      Runtime.getRuntime().addShutdownHook(new Thread(logger::stop));

      Thread.currentThread().join();
    } catch (IllegalArgumentException exception) {
      System.err.println(exception.getMessage());
      MapsValueLoggerArguments.printUsage();
      System.exit(1);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } catch (Exception exception) {
      exception.printStackTrace(System.err);
      System.exit(2);
    }
  }
}