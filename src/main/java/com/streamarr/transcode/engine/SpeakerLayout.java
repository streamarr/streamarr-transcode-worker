package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.Speaker.BC;
import static com.streamarr.transcode.engine.Speaker.BFC;
import static com.streamarr.transcode.engine.Speaker.BFL;
import static com.streamarr.transcode.engine.Speaker.BFR;
import static com.streamarr.transcode.engine.Speaker.BIL;
import static com.streamarr.transcode.engine.Speaker.BIR;
import static com.streamarr.transcode.engine.Speaker.BL;
import static com.streamarr.transcode.engine.Speaker.BR;
import static com.streamarr.transcode.engine.Speaker.DL;
import static com.streamarr.transcode.engine.Speaker.DR;
import static com.streamarr.transcode.engine.Speaker.FC;
import static com.streamarr.transcode.engine.Speaker.FL;
import static com.streamarr.transcode.engine.Speaker.FLC;
import static com.streamarr.transcode.engine.Speaker.FR;
import static com.streamarr.transcode.engine.Speaker.FRC;
import static com.streamarr.transcode.engine.Speaker.LFE;
import static com.streamarr.transcode.engine.Speaker.LFE2;
import static com.streamarr.transcode.engine.Speaker.SL;
import static com.streamarr.transcode.engine.Speaker.SR;
import static com.streamarr.transcode.engine.Speaker.TBC;
import static com.streamarr.transcode.engine.Speaker.TBL;
import static com.streamarr.transcode.engine.Speaker.TBR;
import static com.streamarr.transcode.engine.Speaker.TC;
import static com.streamarr.transcode.engine.Speaker.TFC;
import static com.streamarr.transcode.engine.Speaker.TFL;
import static com.streamarr.transcode.engine.Speaker.TFR;
import static com.streamarr.transcode.engine.Speaker.TSL;
import static com.streamarr.transcode.engine.Speaker.TSR;
import static com.streamarr.transcode.engine.Speaker.WL;
import static com.streamarr.transcode.engine.Speaker.WR;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.NonNull;

/**
 * A channel layout as FFmpeg describes it: its channels in order. A standard layout has FFmpeg's
 * name, such as {@code 5.1(side)}; any other layout is FFmpeg's description of its channels, such
 * as {@code 3 channels (FL+FR+TFL)}.
 */
public record SpeakerLayout(@NonNull String name, @NonNull List<Speaker> speakers) {

  // How FFmpeg describes a layout that has no name.
  private static final Pattern DESCRIPTION = Pattern.compile("\\d+ channels \\(([A-Z0-9+]+)\\)");

  // FFmpeg 8.1's standard layouts, in the order of libavutil's channel_layout_map, whose first
  // entry with a channel count is FFmpeg's default layout for that count.
  private static final List<SpeakerLayout> STANDARD =
      List.of(
          layout("mono", FC),
          layout("stereo", FL, FR),
          layout("2.1", FL, FR, LFE),
          layout("3.0", FL, FR, FC),
          layout("3.0(back)", FL, FR, BC),
          layout("4.0", FL, FR, FC, BC),
          layout("quad", FL, FR, BL, BR),
          layout("quad(side)", FL, FR, SL, SR),
          layout("3.1", FL, FR, FC, LFE),
          layout("5.0", FL, FR, FC, BL, BR),
          layout("5.0(side)", FL, FR, FC, SL, SR),
          layout("4.1", FL, FR, FC, LFE, BC),
          layout("5.1", FL, FR, FC, LFE, BL, BR),
          layout("5.1(side)", FL, FR, FC, LFE, SL, SR),
          layout("6.0", FL, FR, FC, BC, SL, SR),
          layout("6.0(front)", FL, FR, FLC, FRC, SL, SR),
          layout("3.1.2", FL, FR, FC, LFE, TFL, TFR),
          layout("hexagonal", FL, FR, FC, BL, BR, BC),
          layout("6.1", FL, FR, FC, LFE, BC, SL, SR),
          layout("6.1(back)", FL, FR, FC, LFE, BL, BR, BC),
          layout("6.1(front)", FL, FR, LFE, FLC, FRC, SL, SR),
          layout("7.0", FL, FR, FC, BL, BR, SL, SR),
          layout("7.0(front)", FL, FR, FC, FLC, FRC, SL, SR),
          layout("7.1", FL, FR, FC, LFE, BL, BR, SL, SR),
          layout("7.1(wide)", FL, FR, FC, LFE, BL, BR, FLC, FRC),
          layout("7.1(wide-side)", FL, FR, FC, LFE, FLC, FRC, SL, SR),
          layout("5.1.2", FL, FR, FC, LFE, SL, SR, TFL, TFR),
          layout("5.1.2(back)", FL, FR, FC, LFE, BL, BR, TFL, TFR),
          layout("octagonal", FL, FR, FC, BL, BR, BC, SL, SR),
          layout("cube", FL, FR, BL, BR, TFL, TFR, TBL, TBR),
          layout("5.1.4", FL, FR, FC, LFE, SL, SR, TFL, TFR, TBL, TBR),
          layout("7.1.2", FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR),
          layout("7.1.4", FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR, TBL, TBR),
          layout("7.2.3", FL, FR, FC, LFE, BL, BR, SL, SR, TFL, TFR, TBC, LFE2),
          layout("9.1.4", FL, FR, FC, LFE, BL, BR, FLC, FRC, SL, SR, TFL, TFR, TBL, TBR),
          layout("9.1.6", FL, FR, FC, LFE, BL, BR, FLC, FRC, SL, SR, TFL, TFR, TBL, TBR, TSL, TSR),
          layout(
              "hexadecagonal",
              FL,
              FR,
              FC,
              BL,
              BR,
              BC,
              SL,
              SR,
              TFL,
              TFC,
              TFR,
              TBL,
              TBC,
              TBR,
              WL,
              WR),
          layout("binaural", BIL, BIR),
          layout("downmix", DL, DR),
          layout(
              "22.2", FL, FR, FC, LFE, BL, BR, FLC, FRC, BC, SL, SR, TC, TFL, TFC, TFR, TBL, TBC,
              TBR, LFE2, TSL, TSR, BFC, BFL, BFR));

  private static final Map<String, SpeakerLayout> BY_NAME =
      STANDARD.stream()
          .collect(Collectors.toUnmodifiableMap(SpeakerLayout::name, layout -> layout));

  public SpeakerLayout {
    speakers = List.copyOf(speakers);
  }

  /**
   * The layout that ffprobe's or FFmpeg's text names or describes; empty when the text is neither a
   * standard name nor a description whose every channel FFmpeg names.
   */
  public static Optional<SpeakerLayout> parse(String text) {
    var standard = Optional.ofNullable(BY_NAME.get(text));
    if (standard.isPresent()) {
      return standard;
    }

    var description = DESCRIPTION.matcher(text);
    if (!description.matches()) {
      return Optional.empty();
    }

    var names = description.group(1).split("\\+");
    if (!Arrays.stream(names).allMatch(SpeakerLayout::isSpeaker)) {
      return Optional.empty();
    }

    return Optional.of(
        new SpeakerLayout(text, Arrays.stream(names).map(Speaker::valueOf).toList()));
  }

  /** FFmpeg's default layout for the channel count, which it reads a stream without a layout as. */
  public static Optional<SpeakerLayout> defaultFor(int channels) {
    return STANDARD.stream().filter(layout -> layout.speakers.size() == channels).findFirst();
  }

  /** The standard layout of the name; throws when FFmpeg has no layout of that name. */
  public static SpeakerLayout named(String name) {
    return Optional.ofNullable(BY_NAME.get(name))
        .orElseThrow(() -> new IllegalArgumentException("FFmpeg names no layout " + name));
  }

  public int channelCount() {
    return speakers.size();
  }

  public boolean has(Speaker speaker) {
    return speakers.contains(speaker);
  }

  private static boolean isSpeaker(String name) {
    return Arrays.stream(Speaker.values()).anyMatch(speaker -> speaker.name().equals(name));
  }

  private static SpeakerLayout layout(String name, Speaker... speakers) {
    return new SpeakerLayout(name, List.of(speakers));
  }
}
