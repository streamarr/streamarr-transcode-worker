package com.streamarr.transcode.engine;

import java.util.Set;
import lombok.Builder;
import lombok.NonNull;

/**
 * An encoder backend that passed the worker's test encode, with whether ADR 0037 lists its encoder
 * as keyframe-verified, and each output beyond 8-bit 4:2:0 SDR whose own test encode it passed.
 */
@Builder
public record WorkerVerifiedEncoder(
    @NonNull EncoderBackend backend,
    boolean keyframeVerified,
    @NonNull Set<EncodeFeature> encodeFeatures) {

  public WorkerVerifiedEncoder {
    encodeFeatures = Set.copyOf(encodeFeatures);
  }

  // Lombok builder default — its generated setter assigns it
  @SuppressWarnings({"java:S1068", "java:S9438"})
  public static class WorkerVerifiedEncoderBuilder {
    private Set<EncodeFeature> encodeFeatures = Set.of();
  }
}
