package com.streamarr.transcode.worker;

import com.streamarr.transcode.protocol.WorkerIdentityMetadata;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.stub.MetadataUtils;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

final class GrpcWorkerRuntime implements WorkerRuntime {

  private static final int KEEPALIVE_TIME_SECONDS = 30;
  private static final int KEEPALIVE_TIMEOUT_SECONDS = 10;

  @Override
  public ManagedChannel openChannel(
      TranscodeWorkerConfiguration configuration, InetSocketAddress address) throws IOException {
    var headers = new Metadata();
    headers.put(WorkerIdentityMetadata.WORKER_ID, configuration.workerId().toString());
    // Client keepalive detects a half-open control-plane connection (server power loss, dropped
    // NAT mapping); without it an idle worker would wait on a dead session until TCP gives up.
    return NettyChannelBuilder.forAddress(address.getHostString(), address.getPort())
        .usePlaintext()
        .intercept(MetadataUtils.newAttachHeadersInterceptor(headers))
        .keepAliveTime(KEEPALIVE_TIME_SECONDS, TimeUnit.SECONDS)
        .keepAliveTimeout(KEEPALIVE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build();
  }

  @Override
  public ExecutorService newProbeScope() {
    return Executors.newVirtualThreadPerTaskExecutor();
  }
}
