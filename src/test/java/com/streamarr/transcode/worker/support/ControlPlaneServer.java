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
  private Server server;

  public ControlPlaneServer(@NonNull BindableService service) {
    this.service = service;
  }

  public void start() throws IOException {
    server = NettyServerBuilder.forPort(0).addService(service).build().start();
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
