package io.mapsmessaging.tools.testlab;

public final class TestSleeper {

  private TestSleeper() {}

  public static void main(String[] args) throws Exception {
    System.out.println("ready");
    System.out.flush();
    Thread.sleep(60_000);
  }
}
