/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class InspectionPoolChecks {
  private InspectionPoolChecks() {}

  public static void main(String[] args) throws Exception {
    long[] totals = new long[5];
    CountDownLatch entered = new CountDownLatch(4);
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    try (InspectionPool pool = new InspectionPool(4, totals)) {
      for (int i = 0; i < 100; i++) {
        pool.submit(() -> {
          int current = active.incrementAndGet();
          maximum.accumulateAndGet(current, Math::max);
          try {
            entered.countDown();
            if (!entered.await(5, TimeUnit.SECONDS)) throw new IOException("Workers did not overlap");
            return new long[]{1, 2, 3, 4, 5};
          } finally { active.decrementAndGet(); }
        });
      }
      pool.finish();
    }
    if (maximum.get() != 4 || active.get() != 0 || totals[0] != 100 || totals[4] != 500) {
      throw new AssertionError("Worker bound or aggregation failed");
    }
    try (InspectionPool pool = new InspectionPool(2, new long[5])) {
      pool.submit(() -> { throw new IOException("output failed"); });
      try {
        pool.finish();
        throw new AssertionError("Worker failure lost");
      } catch (IOException expected) {
        if (!expected.getMessage().equals("output failed")) throw expected;
      }
    }
    System.out.println("Inspection pool: concurrent execution, worker limit, totals and failure propagation passed");
  }
}
