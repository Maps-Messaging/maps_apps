/*
 *
 *  Copyright [ 2026 ] Ralf Himmelein and Claude
 *
 *  Licensed under the Apache License, Version 2.0 with the Commons Clause
 *  (the "License"); you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *      https://commonsclause.com/
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package io.mapsmessaging.tools.valuelogger;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.google.gson.JsonObject;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogFilesTest {

  @TempDir
  Path tempDir;

  @BeforeEach
  void requirePosix() {
    assumeTrue(tempDir.getFileSystem().supportedFileAttributeViews().contains("posix"));
  }

  @Test
  void rollingWriterCreatesPrivateDirectoriesAndFiles() throws Exception {
    Path outputDir = tempDir.resolve("a").resolve("logs");
    Clock clock = Clock.fixed(Instant.parse("2026-05-26T10:30:00Z"), ZoneOffset.UTC);
    RollingLogWriter writer =
        new RollingLogWriter(outputDir, OutputFormat.JSON, 500, clock, () -> Long.MAX_VALUE);

    writer.open();
    JsonObject logRecord = new JsonObject();
    logRecord.addProperty("topic", "/test");
    writer.write(logRecord);
    writer.close();

    assertEquals("rwxr-x---", permissions(outputDir));
    assertEquals("rwxr-x---", permissions(outputDir.getParent()));
    assertEquals("rw-r-----", permissions(outputDir.resolve("maps-2026-05-26-10.ndjson")));
  }

  @Test
  void csvWriterCreatesPrivateFileWithHeader() throws Exception {
    Path file = tempDir.resolve("values.csv");
    CsvLogWriter writer = new CsvLogWriter(file.toString());

    writer.open();
    writer.close();

    assertEquals("rw-r-----", permissions(file));
    assertTrue(Files.readString(file).startsWith("receivedTimestamp,"));
  }

  @Test
  void existingPathsKeepTheirPermissions() throws Exception {
    Path file = Files.createFile(tempDir.resolve("existing.ndjson"));
    Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-rw-r--"));
    Files.setPosixFilePermissions(tempDir, PosixFilePermissions.fromString("rwxrwxr-x"));

    LogFiles.createDirectories(tempDir);
    LogFiles.createFileIfAbsent(file);

    assertEquals("rw-rw-r--", permissions(file));
    assertEquals("rwxrwxr-x", permissions(tempDir));
  }

  private static String permissions(Path path) throws Exception {
    return PosixFilePermissions.toString(Files.getPosixFilePermissions(path));
  }
}
