package com.streamarr.transcode.engine;

import java.nio.ByteBuffer;

/** Rewrites the bytes of one video sample of a codec as the copy's bitstream rewrites list. */
sealed interface SampleRewrite permits NalUnitSampleRewrite, Av1SampleRewrite {

  /**
   * The sample without what the rewrites strip, or the same buffer when they strip nothing from it.
   * The sample's position and limit bound its bytes, and they stay unchanged.
   *
   * @throws FragmentedMp4Exception with {@link FragmentedMp4Exception.Reason#MALFORMED_BOX} when
   *     the sample cannot be split into its NAL units or OBUs, or an SEI message cannot be read
   */
  ByteBuffer rewrite(ByteBuffer sample);
}
