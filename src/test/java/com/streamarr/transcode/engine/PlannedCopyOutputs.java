package com.streamarr.transcode.engine;

import com.streamarr.transcode.engine.PlannedCopyOutput.Chromaticity;
import com.streamarr.transcode.engine.PlannedCopyOutput.ContentLightLevel;
import com.streamarr.transcode.engine.PlannedCopyOutput.MasteringDisplay;
import com.streamarr.transcode.engine.VideoDynamicRange.CompatibleBase;
import com.streamarr.transcode.engine.VideoDynamicRange.DolbyVision;
import com.streamarr.transcode.engine.VideoDynamicRange.Presence;
import java.util.HexFormat;

/** Planned copy outputs that engine tests share, with the HDR10 values of one mastering. */
final class PlannedCopyOutputs {

  /** BT.2020 primaries, the D65 white point, 1000 cd/m² and 0.005 cd/m². */
  static final MasteringDisplay MASTERING_DISPLAY =
      MasteringDisplay.builder()
          .red(new Chromaticity(34000, 16000))
          .green(new Chromaticity(13250, 34500))
          .blue(new Chromaticity(7500, 3000))
          .whitePoint(new Chromaticity(15635, 16450))
          .maxLuminance(10_000_000)
          .minLuminance(50)
          .build();

  /** MaxCLL 1000 cd/m² and MaxFALL 400 cd/m². */
  static final ContentLightLevel CONTENT_LIGHT_LEVEL = new ContentLightLevel(1000, 400);

  /**
   * The prefix SEI NAL unit of {@link #MASTERING_DISPLAY}, written out by hand from ITU-T H.265
   * D.2.28: its header, payload type 137, payload size 24, green, blue, red and the white point,
   * then the luminances, whose three zero bytes need an emulation prevention byte, and the stop
   * bit.
   */
  static final byte[] MASTERING_DISPLAY_SEI =
      HexFormat.of()
          .parseHex(
              "4E01"
                  + "8918"
                  + "33C286C41D4C0BB884D03E803D134042"
                  + "00989680000003"
                  + "0032"
                  + "80");

  /** The prefix SEI NAL unit of {@link #CONTENT_LIGHT_LEVEL}: payload type 144 and size 4. */
  static final byte[] CONTENT_LIGHT_LEVEL_SEI =
      HexFormat.of().parseHex("4E01" + "9004" + "03E80190" + "80");

  private PlannedCopyOutputs() {}

  static VideoDynamicRange pq() {
    return new VideoDynamicRange(CompatibleBase.PQ, Presence.ABSENT, new DolbyVision.Absent());
  }

  static VideoDynamicRange hlg() {
    return new VideoDynamicRange(CompatibleBase.HLG, Presence.ABSENT, new DolbyVision.Absent());
  }

  /** A PQ output with no static HDR metadata, to which a test adds what it needs. */
  static PlannedCopyOutput.PlannedCopyOutputBuilder pqOutputBuilder() {
    return PlannedCopyOutput.builder().dynamicRange(pq());
  }
}
