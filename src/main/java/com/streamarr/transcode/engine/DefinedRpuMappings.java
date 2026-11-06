package com.streamarr.transcode.engine;

import java.util.HashSet;
import java.util.Set;

/**
 * The mappings that the RPUs since the last random access point defined, which is what a decoder
 * that started there holds. The pinned decoder resolves an RPU that reuses a previous RPU's mapping
 * to the mapping under that vdr_rpu_id among the RPUs since it started, else to mapping 0, and
 * rejects the RPU when it holds neither. A seek lands a decoder on a random access point holding
 * none, while a decoder that played on from earlier still holds every mapping it saw, so only a
 * reuse of a mapping defined since the random access point resolves the same way for both; the
 * conversion refuses every other reuse, the fall-back to mapping 0 included.
 */
final class DefinedRpuMappings {

  private final Set<Long> vdrRpuIds = new HashSet<>();

  /** Forgets every mapping, as a decoder that starts at the random access point holds none. */
  void startAtRandomAccessPoint() {
    vdrRpuIds.clear();
  }

  /**
   * Resolves the RPU's mapping the way every decoder does and keeps the mapping the RPU defines for
   * the RPUs after it.
   *
   * @throws DolbyVisionRpuException when the RPU reuses a previous RPU's mapping that no RPU since
   *     the random access point defined
   */
  void resolve(DolbyVisionRpu rpu) {
    rpu.mapping().ifPresent(mapping -> vdrRpuIds.add(mapping.vdrRpuId()));
    var header = rpu.header();
    if (!header.usePreviousVdrRpu() || vdrRpuIds.contains(header.previousVdrRpuId())) {
      return;
    }

    throw new DolbyVisionRpuException(
        "a reuse of previous RPU "
            + header.previousVdrRpuId()
            + "'s mapping that no RPU since the random access point defines, which a decoder that"
            + " starts there does not hold");
  }
}
