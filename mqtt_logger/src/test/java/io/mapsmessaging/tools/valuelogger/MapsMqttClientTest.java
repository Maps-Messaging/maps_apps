package io.mapsmessaging.tools.valuelogger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.eclipse.paho.client.mqttv3.IMqttToken;
import org.eclipse.paho.client.mqttv3.MqttAsyncClient;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class MapsMqttClientTest {

  @Test
  void connectionOptionsUseExplicitKeepAlive() {
    MqttConnectOptions options = MapsMqttClient.createConnectionOptions();

    assertEquals(MapsMqttClient.KEEP_ALIVE_SECONDS, options.getKeepAliveInterval());
    assertEquals(MapsMqttClient.CONNECTION_TIMEOUT_SECONDS, options.getConnectionTimeout());
    assertFalse(options.isAutomaticReconnect());
  }

  @Test
  void startUsesManualAcksAndSubscribes() throws Exception {
    MapsValueLoggerArguments arguments = arguments();
    MqttAsyncClient client = configuredClient(true);
    ExecutorService reconnectExecutor = mock(ExecutorService.class);
    ScheduledExecutorService healthExecutor = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> healthTask = mock(ScheduledFuture.class);

    when(healthExecutor.scheduleWithFixedDelay(
        any(Runnable.class),
        eq(MapsMqttClient.HEALTH_CHECK_INITIAL_DELAY_SECONDS),
        eq(MapsMqttClient.HEALTH_CHECK_PERIOD_SECONDS),
        eq(java.util.concurrent.TimeUnit.SECONDS)))
        .thenReturn(healthTask);

    MapsMqttClient mqttClient =
        new MapsMqttClient(
            arguments,
            (topic, message, acknowledgement) -> {
            },
            (url, clientId) -> client,
            reconnectExecutor,
            healthExecutor);

    mqttClient.start();

    verify(client).setManualAcks(true);
    verify(client).subscribe("#", 1);

    ArgumentCaptor<MqttConnectOptions> optionsCaptor =
        ArgumentCaptor.forClass(MqttConnectOptions.class);
    verify(client).connect(optionsCaptor.capture());
    assertEquals(
        MapsMqttClient.KEEP_ALIVE_SECONDS,
        optionsCaptor.getValue().getKeepAliveInterval());
  }

  @Test
  void healthCheckReconnectsWithoutConnectionLostCallback() throws Exception {
    MapsValueLoggerArguments arguments = arguments();
    MqttAsyncClient firstClient = configuredClient(false);
    MqttAsyncClient secondClient = configuredClient(true);
    AtomicInteger created = new AtomicInteger();

    ExecutorService reconnectExecutor = mock(ExecutorService.class);
    ScheduledExecutorService healthExecutor = mock(ScheduledExecutorService.class);
    ScheduledFuture<?> healthTask = mock(ScheduledFuture.class);

    ArgumentCaptor<Runnable> healthCheck = ArgumentCaptor.forClass(Runnable.class);
    when(healthExecutor.scheduleWithFixedDelay(
        healthCheck.capture(),
        eq(MapsMqttClient.HEALTH_CHECK_INITIAL_DELAY_SECONDS),
        eq(MapsMqttClient.HEALTH_CHECK_PERIOD_SECONDS),
        eq(java.util.concurrent.TimeUnit.SECONDS)))
        .thenReturn(healthTask);

    MapsMqttClient mqttClient =
        new MapsMqttClient(
            arguments,
            (topic, message, acknowledgement) -> {
            },
            (url, clientId) ->
                created.getAndIncrement() == 0 ? firstClient : secondClient,
            reconnectExecutor,
            healthExecutor);

    mqttClient.start();
    healthCheck.getValue().run();

    ArgumentCaptor<Runnable> reconnect = ArgumentCaptor.forClass(Runnable.class);
    verify(reconnectExecutor).submit(reconnect.capture());
    reconnect.getValue().run();

    verify(firstClient).close(true);
    verify(secondClient).subscribe("#", 1);
    assertEquals(2, created.get());
  }

  private MapsValueLoggerArguments arguments() {
    return MapsValueLoggerArguments.parse(
        new String[]{"--url", "tcp://127.0.0.1:1883"},
        key -> null);
  }

  private MqttAsyncClient configuredClient(boolean connected) throws Exception {
    MqttAsyncClient client = mock(MqttAsyncClient.class);
    IMqttToken connectToken = mock(IMqttToken.class);
    IMqttToken subscribeToken = mock(IMqttToken.class);

    when(client.connect(any(MqttConnectOptions.class))).thenReturn(connectToken);
    when(client.subscribe("#", 1)).thenReturn(subscribeToken);
    when(client.isConnected()).thenReturn(connected);

    return client;
  }
}
