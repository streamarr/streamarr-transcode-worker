package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.IsoBoxes.NON_SYNC_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.SYNC_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_DATA_OFFSET;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_FIRST_SAMPLE_FLAGS;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_SAMPLE_COMPOSITION_TIME_OFFSET;
import static com.streamarr.transcode.engine.IsoBoxes.TRUN_SAMPLE_DURATION;
import static com.streamarr.transcode.engine.IsoBoxes.VERSION_1;
import static com.streamarr.transcode.engine.IsoBoxes.fullBox;
import static com.streamarr.transcode.engine.IsoBoxes.u32;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.ByteBuffer;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Track Run Tests")
class TrackRunTest {

  // A run asks for a default only for a field that it leaves out.
  private static final TrackRun.Defaults DEFAULTS =
      new TrackRun.Defaults(
          () -> {
            throw new AssertionError("the run states every sample's duration");
          },
          () -> {
            throw new AssertionError("the run states every sample's size");
          },
          () -> NON_SYNC_SAMPLE_FLAGS);

  @Test
  @DisplayName(
      "Should keep the run's first-sample flags and each sample's duration, flags and negative"
          + " composition offset when it rewrites the run with new sizes and a data offset")
  void
      shouldKeepTheRunsFirstSampleFlagsAndEachSamplesDurationFlagsAndNegativeCompositionOffsetWhenItRewritesTheRunWithNewSizesAndADataOffset() {
    // ISO/IEC 14496-12 8.8.8: version 1 composition offsets are signed.
    var run =
        TrackRun.of(
            boxView(
                fullBox(
                    "trun",
                    VERSION_1
                        | TRUN_DATA_OFFSET
                        | TRUN_FIRST_SAMPLE_FLAGS
                        | TRUN_SAMPLE_DURATION
                        | TRUN_SAMPLE_COMPOSITION_TIME_OFFSET,
                    u32(2),
                    u32(100),
                    u32(SYNC_SAMPLE_FLAGS),
                    u32(1024),
                    u32(-512),
                    u32(2048),
                    u32(512))));

    var rewritten = TrackRun.of(boxView(run.rewritten(200, new long[] {7, 9})));

    assertThat(rewritten.dataOffset()).contains(200);
    assertThat(rewritten.entries(DEFAULTS))
        .containsExactly(
            entry().duration(1024).size(7).flags(SYNC_SAMPLE_FLAGS).compositionOffset(-512).build(),
            entry()
                .duration(2048)
                .size(9)
                .flags(NON_SYNC_SAMPLE_FLAGS)
                .compositionOffset(512)
                .build());
  }

  @Test
  @DisplayName(
      "Should state a sample's negative composition offset when it writes a run of that one sample")
  void shouldStateASamplesNegativeCompositionOffsetWhenItWritesARunOfThatOneSample() {
    var sample =
        entry()
            .duration(1001)
            .size(5)
            .flags(NON_SYNC_SAMPLE_FLAGS)
            .compositionOffset(-1001)
            .build();

    var run = TrackRun.of(boxView(TrackRun.ofOneSample(64, sample)));

    assertThat(run.dataOffset()).contains(64);
    assertThat(run.entries(DEFAULTS)).isEqualTo(List.of(sample));
  }

  private static TrackRun.Entry.EntryBuilder entry() {
    return TrackRun.Entry.builder();
  }

  private static BoxView boxView(byte[] box) {
    return new BoxView("trun", ByteBuffer.wrap(box, 8, box.length - 8));
  }
}
