package com.streamarr.transcode.worker.support;

import static org.assertj.core.api.Assertions.assertThat;

import io.grpc.BindableService;
import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import java.io.IOException;
import java.util.concurrent.TimeUnit;
import lombok.NonNull;

/** A scripted control plane's gRPC server on an ephemeral port. */
public final class ControlPlaneServer implements AutoCloseable {

  private final BindableService service;
  private final boolean callbacksInFrameOrder;
  private Server server;

  public ControlPlaneServer(@NonNull BindableService service) {
    this(service, false);
  }

  private ControlPlaneServer(BindableService service, boolean callbacksInFrameOrder) {
    this.service = service;
    this.callbacksInFrameOrder = callbacksInFrameOrder;
  }

  /**
   * A server that runs the service's callbacks on the connection's transport thread. The callbacks
   * of every call on one connection then run in the order their frames arrived, so they must not
   * block.
   */
  public static ControlPlaneServer withCallbacksInFrameOrder(@NonNull BindableService service) {
    return new ControlPlaneServer(service, true);
  }

  public void start() throws IOException {
    var builder = NettyServerBuilder.forPort(0).addService(service);
    if (callbacksInFrameOrder) {
      builder.directExecutor();
    }

    server = builder.build().start();
  }

  public int port() {
    return server.getPort();
  }

  @Override
  public void close() throws InterruptedException {
    if (server == null) {
      return;
    }
    server.shutdownNow();
    // A hang guard: a JUnit timeout interrupts only once, and a wait in the test body may
    // already have consumed that interrupt.
    assertThat(server.awaitTermination(1, TimeUnit.MINUTES))
        .as("the control-plane server terminates")
        .isTrue();
  }
}
