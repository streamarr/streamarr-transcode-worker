package com.streamarr.transcode.worker;

import io.grpc.ManagedChannel;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutorService;

public interface WorkerRuntime {

  ManagedChannel openChannel(TranscodeWorkerConfiguration configuration, InetSocketAddress address)
      throws IOException;

  ExecutorService newProbeScope();
}
