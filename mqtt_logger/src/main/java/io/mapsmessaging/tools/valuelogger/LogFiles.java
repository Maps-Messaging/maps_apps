package io.mapsmessaging.tools.valuelogger;

import java.io.IOException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Set;

/**
 * Creates log directories and files with owner/group-only permissions.
 *
 * <p>Log records carry full message payloads, so they must not be world-readable regardless of
 * the process umask. Permissions are applied only when a path is created; existing paths are left
 * as the operator configured them. On file systems without POSIX permissions the platform
 * defaults apply.
 */
final class LogFiles {

  static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
      PosixFilePermissions.fromString("rwxr-x---");
  static final Set<PosixFilePermission> FILE_PERMISSIONS =
      PosixFilePermissions.fromString("rw-r-----");

  private LogFiles() {
  }

  static void createDirectories(Path directory) throws IOException {
    if (Files.isDirectory(directory)) {
      return;
    }

    if (!supportsPosix(directory)) {
      Files.createDirectories(directory);
      return;
    }

    Path parent = directory.toAbsolutePath().getParent();
    if (parent != null) {
      createDirectories(parent);
    }

    try {
      Files.createDirectory(directory, attribute(DIRECTORY_PERMISSIONS));
      // The umask may have removed bits; set the intended mode explicitly.
      Files.setPosixFilePermissions(directory, DIRECTORY_PERMISSIONS);
    } catch (FileAlreadyExistsException ignored) {
      // Created concurrently; leave the existing directory untouched.
    }
  }

  static void createFileIfAbsent(Path file) throws IOException {
    if (Files.exists(file) || !supportsPosix(file)) {
      return;
    }

    try {
      Files.createFile(file, attribute(FILE_PERMISSIONS));
    } catch (FileAlreadyExistsException ignored) {
      // Created concurrently; leave the existing file untouched.
    }
  }

  private static boolean supportsPosix(Path path) {
    Path existing = path.toAbsolutePath();

    while (existing != null && !Files.exists(existing)) {
      existing = existing.getParent();
    }

    return existing != null
        && existing.getFileSystem().supportedFileAttributeViews().contains("posix");
  }

  private static FileAttribute<Set<PosixFilePermission>> attribute(
      Set<PosixFilePermission> permissions) {
    return PosixFilePermissions.asFileAttribute(permissions);
  }
}
