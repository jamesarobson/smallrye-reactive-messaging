package io.smallrye.reactive.messaging.mqtt;

import io.reactivex.BackpressureStrategy;
import io.reactivex.Flowable;
import io.reactivex.Observable;
import io.smallrye.reactive.messaging.spi.ConfigurationHelper;
import io.vertx.mqtt.MqttClientOptions;
import io.vertx.reactivex.core.Vertx;
import io.vertx.reactivex.mqtt.MqttClient;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.reactivestreams.Publisher;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class MqttSource {

  private final String host;
  private final int port;
  private final MqttClient client;
  private final String server;
  private final String topic;
  private final int qos;
  private final boolean broadcast;

  public MqttSource(Vertx vertx, Map<String, String> config) {
    ConfigurationHelper conf = ConfigurationHelper.create(config);
    MqttClientOptions options = new MqttClientOptions();
    options.setClientId(conf.get("client-id"));
    options.setAutoGeneratedClientId(conf.getAsBoolean("auto-generated-client-id", false));
    options.setAutoKeepAlive(conf.getAsBoolean("auto-keep-alive", true));
    options.setSsl(conf.getAsBoolean("ssl", false));
    options.setWillQoS(conf.getAsInteger("will-qos", 0));
    options.setKeepAliveTimeSeconds(conf.getAsInteger("keep-alive-seconds", 30));
    options.setMaxInflightQueue(conf.getAsInteger("max-inflight-queue", 10));
    options.setCleanSession(conf.getAsBoolean("auto-clean-session", true));
    options.setWillFlag(conf.getAsBoolean("will-flag", false));
    options.setWillRetain(conf.getAsBoolean("will-retain", false));
    options.setMaxMessageSize(conf.getAsInteger("max-message-size", -1));
    options.setReconnectAttempts(conf.getAsInteger("reconnect-attempts", 5));
    options.setReconnectInterval(TimeUnit.SECONDS.toMillis(conf.getAsInteger("reconnect-interval-seconds", 1)));
    options.setUsername(conf.get("username"));
    options.setPassword(conf.get("password"));
    options.setConnectTimeout((int) TimeUnit.SECONDS.toMillis(conf.getAsInteger("connect-timeout-seconds", 60)));
    options.setTrustAll(conf.getAsBoolean("trust-all", false));

    host = conf.getOrDie("host");
    port = conf.getAsInteger("port", options.isSsl() ? 8883 : 1883);
    server = conf.get("server-name");
    topic = conf.getOrDie("topic");
    client = MqttClient.create(vertx, options);
    qos = conf.getAsInteger("qos", 0);
    broadcast = conf.getAsBoolean("broadcast", false);
  }

  public CompletableFuture<Publisher<? extends Message>> initialize() {
    CompletableFuture<Publisher<? extends Message>> future = new CompletableFuture<>();
    client.rxConnect(port, host, server)
      .subscribe(
        x -> {
          Observable<Message> stream = Observable.create(emitter -> {
            client.publishHandler(message -> emitter.onNext(new MqttMessage(message)));
            client.subscribe(topic, qos, done -> {
              if (done.failed()) {
                // Report on the flow
                emitter.onError(done.cause());
              }
            });
          });
          Flowable<Message> flowable = stream.toFlowable(BackpressureStrategy.BUFFER);
          if (broadcast) {
            flowable = flowable.publish().autoConnect();
          }
          future.complete(flowable);
        },
        future::completeExceptionally
      );
    return future;
  }

  /**
   * For testing purpose only.
   * @return
   */
  public Flowable<MqttMessage> getSource() {
    return (Flowable<MqttMessage>) initialize().join();
  }

  // TODO Disconnect


}
