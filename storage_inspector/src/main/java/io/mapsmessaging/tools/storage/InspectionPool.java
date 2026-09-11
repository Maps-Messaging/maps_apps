/* Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause. */
package io.mapsmessaging.tools.storage;

import java.io.IOException;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Producer backpressure bounds queued and completed-but-uncollected work to twice the worker count. */
final class InspectionPool implements AutoCloseable {
  private final ExecutorService executor;
  private final ExecutorCompletionService<long[]> completion;
  private final int limit;
  private final long[] totals;
  private int outstanding;

  InspectionPool(int threads, long[] totals) {
    executor = Executors.newFixedThreadPool(threads);
    completion = new ExecutorCompletionService<>(executor);
    limit = threads * 2;
    this.totals = totals;
  }

  void submit(Callable<long[]> task) throws IOException {
    if (outstanding == limit) collect();
    completion.submit(task);
    outstanding++;
  }

  void finish() throws IOException {
    while (outstanding > 0) collect();
  }

  private void collect() throws IOException {
    try {
      long[] result = completion.take().get();
      outstanding--;
      for (int i = 0; i < totals.length; i++) totals[i] += result[i];
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("Inspection interrupted", e);
    } catch (ExecutionException e) {
      Throwable cause = e.getCause();
      if (cause instanceof IOException io) throw io;
      if (cause instanceof Error error) throw error;
      throw new IOException("Inspection worker failed", cause);
    }
  }

  @Override public void close() {
    executor.shutdownNow();
    boolean interrupted = Thread.interrupted();
    try {
      // Writers belong to the caller: workers must stop before those writers can close.
      while (!executor.isTerminated()) {
        try { executor.awaitTermination(1, TimeUnit.SECONDS); }
        catch (InterruptedException e) { interrupted = true; }
      }
    } finally {
      if (interrupted) Thread.currentThread().interrupt();
    }
  }
}
