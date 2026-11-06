package com.streamarr.transcode.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * The mappings that the RPUs since the last random access point defined, which is what a decoder
 * that started there holds. The pinned decoder resolves an RPU that reuses a previous RPU's mapping
 * to the mapping under that vdr_rpu_id, else to mapping 0, and rejects the RPU when it holds
 * neither; a seek lands a decoder on a random access point holding none, so the conversion resolves
 * references from each one the same way.
 */
final class DefinedRpuMappings {

  private static final long FALLBACK_VDR_RPU_ID = 0;

  private final Set<Long> vdrRpuIds = new HashSet<>();

  /** Forgets every mapping, as a decoder that starts at the random access point holds none. */
  void startAtRandomAccessPoint() {
    vdrRpuIds.clear();
  }

  /**
   * Resolves the RPU's mapping the way the decoder does and keeps the mapping the RPU defines for
   * the RPUs after it.
   *
   * @throws DolbyVisionRpuException when the RPU reuses a previous RPU's mapping that no RPU since
   *     the random access point defined, and none defined mapping 0
   */
  void resolve(DolbyVisionRpu rpu) {
    rpu.mapping().ifPresent(mapping -> vdrRpuIds.add(mapping.vdrRpuId()));
    if (!rpu.header().usePreviousVdrRpu() || resolves(rpu.header().previousVdrRpuId())) {
      return;
    }

    throw new DolbyVisionRpuException(
        "a reuse of previous RPU "
            + rpu.header().previousVdrRpuId()
            + "'s mapping where no RPU since the random access point defined it or mapping 0, which"
            + " the decoder rejects");
  }

  private boolean resolves(long vdrRpuId) {
    return vdrRpuIds.contains(vdrRpuId) || vdrRpuIds.contains(FALLBACK_VDR_RPU_ID);
  }
}
