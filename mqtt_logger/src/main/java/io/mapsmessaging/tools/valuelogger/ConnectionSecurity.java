package io.mapsmessaging.tools.valuelogger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.net.ssl.KeyManager;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Reads connection secrets from files and builds the TLS socket factory.
 *
 * <p>Secrets are only ever read from files so they never appear on the command line, in the
 * environment or in the process listing. Files are re-read on every (re)connect, so a rotated
 * password or certificate is picked up without restarting the logger.
 */
final class ConnectionSecurity {

  private ConnectionSecurity() {
  }

  static char[] readSecret(String file) throws IOException {
    String secret = Files.readString(Path.of(file), StandardCharsets.UTF_8);
    int end = secret.length();

    while (end > 0 && (secret.charAt(end - 1) == '\n' || secret.charAt(end - 1) == '\r')) {
      end--;
    }

    return secret.substring(0, end).toCharArray();
  }

  static SSLSocketFactory createSocketFactory(MapsValueLoggerArguments arguments)
      throws IOException, GeneralSecurityException {
    KeyManager[] keyManagers = null;
    TrustManager[] trustManagers = null;

    if (arguments.getKeyStore() != null) {
      char[] password = readSecret(arguments.getKeyStorePasswordFile());
      try {
        KeyManagerFactory factory =
            KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        factory.init(loadStore(arguments.getKeyStore(), password), password);
        keyManagers = factory.getKeyManagers();
      } finally {
        Arrays.fill(password, '\0');
      }
    }

    if (arguments.getTrustStore() != null) {
      char[] password = readSecret(arguments.getTrustStorePasswordFile());
      try {
        TrustManagerFactory factory =
            TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(loadStore(arguments.getTrustStore(), password));
        trustManagers = factory.getTrustManagers();
      } finally {
        Arrays.fill(password, '\0');
      }
    }

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers, trustManagers, null);
    return context.getSocketFactory();
  }

  private static KeyStore loadStore(String file, char[] password)
      throws IOException, GeneralSecurityException {
    // KeyStore.getInstance(File, ...) detects PKCS12 and JKS from the file content.
    return KeyStore.getInstance(new File(file), password);
  }
}
