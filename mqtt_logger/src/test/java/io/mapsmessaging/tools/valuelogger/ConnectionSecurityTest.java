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

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionSecurityTest {

  private static final String STORE_PASSWORD = "changeit";

  @TempDir
  Path tempDir;

  private Path storePasswordFile;

  @BeforeEach
  void setUp() throws Exception {
    storePasswordFile = Files.writeString(tempDir.resolve("store-password"), STORE_PASSWORD + "\n");
  }

  @Test
  void readSecretStripsTrailingLineBreaksOnly() throws Exception {
    Path file = Files.writeString(tempDir.resolve("secret"), " pa ss\r\n\n");
    assertArrayEquals(" pa ss".toCharArray(), ConnectionSecurity.readSecret(file.toString()));
  }

  @Test
  void mutualTlsHandshakeSucceedsWithClientCertificate() throws Exception {
    Stores stores = createStores();
    MapsValueLoggerArguments arguments = tlsArguments(stores.clientKeyStore, stores.clientTrustStore);
    SSLSocketFactory factory = ConnectionSecurity.createSocketFactory(arguments);

    MqttConnectOptions options = MapsMqttClient.createConnectionOptions(arguments);
    assertNotNull(options.getSocketFactory());
    assertTrue(options.isHttpsHostnameVerificationEnabled());

    try (SSLServerSocket server = startServer(stores)) {
      CompletableFuture<String> peer = acceptOne(server);

      try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", server.getLocalPort())) {
        socket.startHandshake();
        assertEquals("CN=localhost", socket.getSession().getPeerPrincipal().getName());
      }

      assertEquals("CN=maps-logger", peer.get(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void serverRejectsClientWithoutCertificate() throws Exception {
    Stores stores = createStores();
    MapsValueLoggerArguments arguments = tlsArguments(null, stores.clientTrustStore);
    SSLSocketFactory factory = ConnectionSecurity.createSocketFactory(arguments);

    try (SSLServerSocket server = startServer(stores)) {
      CompletableFuture<String> peer = acceptOne(server);

      try (SSLSocket socket = (SSLSocket) factory.createSocket("127.0.0.1", server.getLocalPort())) {
        socket.startHandshake();
        // TLS 1.3 reports the missing client certificate after the client's handshake completes.
        socket.getInputStream().read();
      } catch (Exception expected) {
        // Either side may observe the failure first.
      }

      Exception failure = assertThrows(Exception.class, () -> peer.get(10, TimeUnit.SECONDS));
      assertNotNull(failure.getCause());
    }
  }

  private MapsValueLoggerArguments tlsArguments(Path keyStore, Path trustStore) {
    List<String> args = new ArrayList<>(List.of("--url", "ssl://127.0.0.1:1895"));

    if (keyStore != null) {
      args.addAll(List.of("--keystore", keyStore.toString(),
          "--keystore-password-file", storePasswordFile.toString()));
    }

    args.addAll(List.of("--truststore", trustStore.toString(),
        "--truststore-password-file", storePasswordFile.toString()));

    return MapsValueLoggerArguments.parse(args.toArray(String[]::new), key -> null);
  }

  private SSLServerSocket startServer(Stores stores) throws Exception {
    char[] password = STORE_PASSWORD.toCharArray();
    KeyManagerFactory keyManagers =
        KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
    keyManagers.init(KeyStore.getInstance(stores.serverKeyStore.toFile(), password), password);
    TrustManagerFactory trustManagers =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    trustManagers.init(KeyStore.getInstance(stores.serverTrustStore.toFile(), password));

    SSLContext context = SSLContext.getInstance("TLS");
    context.init(keyManagers.getKeyManagers(), trustManagers.getTrustManagers(), null);

    SSLServerSocket server =
        (SSLServerSocket) context.getServerSocketFactory().createServerSocket(0);
    server.setNeedClientAuth(true);
    return server;
  }

  private CompletableFuture<String> acceptOne(SSLServerSocket server) {
    return CompletableFuture.supplyAsync(() -> {
      try (SSLSocket socket = (SSLSocket) server.accept()) {
        socket.startHandshake();
        return socket.getSession().getPeerPrincipal().getName();
      } catch (Exception exception) {
        throw new IllegalStateException(exception);
      }
    });
  }

  private Stores createStores() throws Exception {
    Stores stores = new Stores(
        tempDir.resolve("server.p12"),
        tempDir.resolve("server-trust.p12"),
        tempDir.resolve("client.p12"),
        tempDir.resolve("client-trust.p12"));

    keytool("-genkeypair", "-alias", "server", "-keyalg", "EC", "-dname", "CN=localhost",
        "-ext", "SAN=ip:127.0.0.1", "-keystore", stores.serverKeyStore.toString());
    keytool("-genkeypair", "-alias", "client", "-keyalg", "EC", "-dname", "CN=maps-logger",
        "-keystore", stores.clientKeyStore.toString());

    Path serverCertificate = tempDir.resolve("server.crt");
    Path clientCertificate = tempDir.resolve("client.crt");
    keytool("-exportcert", "-alias", "server", "-keystore", stores.serverKeyStore.toString(),
        "-file", serverCertificate.toString());
    keytool("-exportcert", "-alias", "client", "-keystore", stores.clientKeyStore.toString(),
        "-file", clientCertificate.toString());
    keytool("-importcert", "-noprompt", "-alias", "server", "-file", serverCertificate.toString(),
        "-keystore", stores.clientTrustStore.toString());
    keytool("-importcert", "-noprompt", "-alias", "client", "-file", clientCertificate.toString(),
        "-keystore", stores.serverTrustStore.toString());

    return stores;
  }

  private void keytool(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
    command.addAll(List.of(arguments));
    command.addAll(List.of("-storetype", "PKCS12", "-storepass", STORE_PASSWORD));

    Process process = new ProcessBuilder(command)
        .redirectErrorStream(true)
        .redirectOutput(new File(tempDir.toFile(), "keytool.log"))
        .start();

    assertTrue(process.waitFor(60, TimeUnit.SECONDS), "keytool timed out");
    assertEquals(0, process.exitValue(),
        () -> "keytool failed: " + readLog());
  }

  private String readLog() {
    try {
      return Files.readString(tempDir.resolve("keytool.log"));
    } catch (Exception exception) {
      return exception.getMessage();
    }
  }

  private record Stores(
      Path serverKeyStore,
      Path serverTrustStore,
      Path clientKeyStore,
      Path clientTrustStore) {
  }
}
