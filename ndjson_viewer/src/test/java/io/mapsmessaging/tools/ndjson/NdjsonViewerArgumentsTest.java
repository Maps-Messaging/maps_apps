/*
 * Copyright [ 2024 - 2026 ] MapsMessaging B.V.
 * Licensed under the Apache License, Version 2.0 with the Commons Clause.
 */
package io.mapsmessaging.tools.ndjson;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class NdjsonViewerArgumentsTest {

  @Test
  void parsesQueryAndPersistentDatabase() {
    NdjsonViewerArguments arguments = NdjsonViewerArguments.parse(new String[] {
        "logs", "--database", "analysis.duckdb", "--sql", "SELECT * FROM mavlink_log", "--format", "csv"
    });

    assertEquals(Path.of("logs"), arguments.input());
    assertEquals(Path.of("analysis.duckdb"), arguments.database());
    assertEquals("SELECT * FROM mavlink_log", arguments.sql());
    assertEquals(QueryOutputFormat.CSV, arguments.format());
  }

  @Test
  void opensExistingDatabaseWithoutInputPath() {
    NdjsonViewerArguments arguments = NdjsonViewerArguments.parse(
        new String[] {"--database", "analysis.duckdb", "--sql", "SELECT * FROM maps_log"});

    assertNull(arguments.input());
    assertEquals(Path.of("analysis.duckdb"), arguments.database());
    assertEquals("SELECT * FROM maps_log", arguments.sql());
  }

  @Test
  void rejectsOptionsWithoutInputOrDatabase() {
    IllegalArgumentException exception = assertThrows(
        IllegalArgumentException.class,
        () -> NdjsonViewerArguments.parse(new String[] {"--sql", "SELECT 1"}));

    assertEquals(
        "Either an input file/directory or --database <file> is required",
        exception.getMessage());
  }

  @Test
  void infersNdjsonForOutputFile() {
    NdjsonViewerArguments arguments = NdjsonViewerArguments.parse(
        new String[] {"log.ndjson", "--topics", "--output", "topics.ndjson"});

    assertTrue(arguments.topics());
    assertEquals(QueryOutputFormat.NDJSON, arguments.format());
  }

  @Test
  void parsesRawFormat() {
    NdjsonViewerArguments arguments = NdjsonViewerArguments.parse(
        new String[] {"log.ndjson", "--sql", "SELECT payload FROM maps_log", "--format", "raw"});

    assertEquals(QueryOutputFormat.RAW, arguments.format());
  }

  @Test
  void enablesRawOutputAlias() {
    NdjsonViewerArguments arguments = NdjsonViewerArguments.parse(
        new String[] {"log.ndjson", "--sql", "SELECT payload FROM maps_log", "--raw"});

    assertEquals(QueryOutputFormat.RAW, arguments.format());
  }

  @Test
  void rejectsTopicsAndSqlTogether() {
    assertThrows(
        IllegalArgumentException.class,
        () -> NdjsonViewerArguments.parse(
            new String[] {"log.ndjson", "--topics", "--sql", "SELECT 1"}));
  }

  @Test
  void enablesInteractiveModeExplicitly() {
    NdjsonViewerArguments arguments =
        NdjsonViewerArguments.parse(new String[] {"log.ndjson", "--interactive"});

    assertTrue(arguments.interactive());
  }

  @Test
  void rejectsInteractiveModeWithAnotherOperation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NdjsonViewerArguments.parse(
                new String[] {"log.ndjson", "--interactive", "--topics"}));
  }

  @Test
  void enablesDuckDbUiMode() {
    NdjsonViewerArguments arguments =
        NdjsonViewerArguments.parse(new String[] {"log.ndjson", "-ui"});

    assertTrue(arguments.ui());
  }

  @Test
  void enablesDuckDbUiLongAlias() {
    NdjsonViewerArguments arguments =
        NdjsonViewerArguments.parse(new String[] {"--database", "analysis.duckdb", "--ui"});

    assertTrue(arguments.ui());
  }

  @Test
  void rejectsDuckDbUiWithAnotherOperation() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            NdjsonViewerArguments.parse(
                new String[] {"log.ndjson", "-ui", "--topics"}));
  }
}
