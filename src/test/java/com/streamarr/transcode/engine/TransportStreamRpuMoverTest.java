package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.TransportStreams.AUD;
import static com.streamarr.transcode.engine.TransportStreams.AUDIO_PID;
import static com.streamarr.transcode.engine.TransportStreams.H264;
import static com.streamarr.transcode.engine.TransportStreams.HEVC;
import static com.streamarr.transcode.engine.TransportStreams.IDR;
import static com.streamarr.transcode.engine.TransportStreams.PPS;
import static com.streamarr.transcode.engine.TransportStreams.PREFIX_SEI;
import static com.streamarr.transcode.engine.TransportStreams.RPU;
import static com.streamarr.transcode.engine.TransportStreams.SPS;
import static com.streamarr.transcode.engine.TransportStreams.TRAIL_R;
import static com.streamarr.transcode.engine.TransportStreams.VIDEO_PID;
import static com.streamarr.transcode.engine.TransportStreams.VPS;
import static com.streamarr.transcode.engine.TransportStreams.accessUnit;
import static com.streamarr.transcode.engine.TransportStreams.concat;
import static com.streamarr.transcode.engine.TransportStreams.nalUnit;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Transport Stream RPU Mover Tests")
class TransportStreamRpuMoverTest {

  private static final byte[] AUDIO_FRAME = new byte[] {(byte) 0xFF, (byte) 0xF1, 0x50, 0x40};

  @Test
  @DisplayName(
      "Should move the RPU ahead of the access unit delimiter when it precedes the first slice")
  void shouldMoveTheRpuAheadOfTheAccessUnitDelimiterWhenItPrecedesTheFirstSlice()
      throws IOException {
    var source = hevc().pes(VIDEO_PID, accessUnit(AUD, RPU, TRAIL_R)).bytes();

    var moved = moved(source);

    assertThat(moved).isEqualTo(hevc().pes(VIDEO_PID, accessUnit(RPU, AUD, TRAIL_R)).bytes());
  }

  @Test
  @DisplayName(
      "Should move the RPU ahead of the parameter sets and the delimiter when they precede it")
  void shouldMoveTheRpuAheadOfTheParameterSetsAndTheDelimiterWhenTheyPrecedeIt()
      throws IOException {
    var source =
        hevc().pes(VIDEO_PID, accessUnit(AUD, VPS, SPS, PPS, PREFIX_SEI, RPU, IDR)).bytes();

    var moved = moved(source);

    assertThat(moved)
        .isEqualTo(
            hevc().pes(VIDEO_PID, accessUnit(RPU, AUD, VPS, SPS, PPS, PREFIX_SEI, IDR)).bytes());
  }

  @Test
  @DisplayName("Should leave each access unit unchanged when its RPU follows its picture")
  void shouldLeaveEachAccessUnitUnchangedWhenItsRpuFollowsItsPicture() throws IOException {
    var source =
        hevc()
            .pes(VIDEO_PID, accessUnit(AUD, VPS, SPS, PPS, IDR, RPU))
            .pes(VIDEO_PID, accessUnit(AUD, TRAIL_R, RPU))
            .bytes();

    assertThat(moved(source)).isEqualTo(source);
  }

  @Test
  @DisplayName("Should leave the access unit unchanged when the RPU already starts it")
  void shouldLeaveTheAccessUnitUnchangedWhenTheRpuAlreadyStartsIt() throws IOException {
    var source = hevc().pes(VIDEO_PID, accessUnit(RPU, AUD, TRAIL_R)).bytes();

    assertThat(moved(source)).isEqualTo(source);
  }

  @Test
  @DisplayName(
      "Should move the RPU of a payload that holds no picture when the next payload ends it")
  void shouldMoveTheRpuOfAPayloadThatHoldsNoPictureWhenTheNextPayloadEndsIt() throws IOException {
    var source =
        hevc()
            .pes(VIDEO_PID, accessUnit(AUD, TRAIL_R))
            .pes(VIDEO_PID, accessUnit(AUD, RPU))
            .pes(VIDEO_PID, accessUnit(AUD, TRAIL_R))
            .bytes();

    var moved = moved(source);

    assertThat(moved)
        .isEqualTo(
            hevc()
                .pes(VIDEO_PID, accessUnit(AUD, TRAIL_R))
                .pes(VIDEO_PID, accessUnit(RPU, AUD))
                .pes(VIDEO_PID, accessUnit(AUD, TRAIL_R))
                .bytes());
  }

  @Test
  @DisplayName("Should move the RPU of the last payload when the stream ends after it")
  void shouldMoveTheRpuOfTheLastPayloadWhenTheStreamEndsAfterIt() throws IOException {
    var source =
        hevc()
            .pes(VIDEO_PID, accessUnit(AUD, RPU, TRAIL_R))
            .pes(VIDEO_PID, accessUnit(AUD, RPU))
            .bytes();

    var moved = moved(source);

    assertThat(moved)
        .isEqualTo(
            hevc()
                .pes(VIDEO_PID, accessUnit(RPU, AUD, TRAIL_R))
                .pes(VIDEO_PID, accessUnit(RPU, AUD))
                .bytes());
  }

  @Test
  @DisplayName(
      "Should move an RPU across packet boundaries when the leading units span several packets")
  void shouldMoveAnRpuAcrossPacketBoundariesWhenTheLeadingUnitsSpanSeveralPackets()
      throws IOException {
    var parameterSets = concat(nalUnit(VPS, 150), nalUnit(SPS, 210), nalUnit(PPS, 90));
    var rpu = nalUnit(RPU, 700);
    var slice = nalUnit(IDR, 5000);
    var source = hevc().pes(VIDEO_PID, concat(nalUnit(AUD, 1), parameterSets, rpu, slice)).bytes();

    var moved = moved(source);

    assertThat(moved)
        .hasSameSizeAs(source)
        .isEqualTo(
            hevc().pes(VIDEO_PID, concat(rpu, nalUnit(AUD, 1), parameterSets, slice)).bytes());
  }

  @Test
  @DisplayName(
      "Should keep other streams' packets in their places when they arrive while an RPU is held")
  void shouldKeepOtherStreamsPacketsInTheirPlacesWhenTheyArriveWhileAnRpuIsHeld()
      throws IOException {
    var rpu = nalUnit(RPU, 400);
    var slice = nalUnit(TRAIL_R, 600);
    var accessUnit = concat(nalUnit(AUD, 1), rpu, slice);
    var source = interleaved(hevc(), accessUnit).pes(VIDEO_PID, accessUnit(AUD, TRAIL_R)).bytes();

    var moved = moved(source);

    assertThat(moved)
        .isEqualTo(
            interleaved(hevc(), concat(rpu, nalUnit(AUD, 1), slice))
                .pes(VIDEO_PID, accessUnit(AUD, TRAIL_R))
                .bytes());
  }

  @Test
  @DisplayName("Should leave the stream unchanged when its video is not HEVC")
  void shouldLeaveTheStreamUnchangedWhenItsVideoIsNotHevc() throws IOException {
    var source =
        TransportStreams.withProgram(H264).pes(VIDEO_PID, accessUnit(AUD, RPU, TRAIL_R)).bytes();

    assertThat(moved(source)).isEqualTo(source);
  }

  @Test
  @DisplayName("Should pass trailing bytes through when the stream ends inside a packet")
  void shouldPassTrailingBytesThroughWhenTheStreamEndsInsideAPacket() throws IOException {
    var partialPacket = new byte[] {0x47, 0x01, 0x00, 0x10, 1, 2, 3};
    var source =
        hevc().pes(VIDEO_PID, accessUnit(AUD, RPU, TRAIL_R)).trailing(partialPacket).bytes();

    var moved = moved(source);

    assertThat(moved)
        .isEqualTo(
            hevc().pes(VIDEO_PID, accessUnit(RPU, AUD, TRAIL_R)).trailing(partialPacket).bytes());
  }

  private static TransportStreams hevc() {
    return TransportStreams.withProgram(HEVC);
  }

  // The access unit's first packet, then an audio packet, then the rest of the access unit: the
  // reader holds the audio packet together with the video packets around it.
  private static TransportStreams interleaved(TransportStreams stream, byte[] accessUnit) {
    var whole = new TransportStreams().pes(VIDEO_PID, accessUnit).bytes();
    var audio = new TransportStreams().pes(AUDIO_PID, AUDIO_FRAME).bytes();
    return stream
        .trailing(Arrays.copyOfRange(whole, 0, TransportStreams.PACKET_BYTES))
        .trailing(audio)
        .trailing(Arrays.copyOfRange(whole, TransportStreams.PACKET_BYTES, whole.length));
  }

  private static byte[] moved(byte[] source) throws IOException {
    var output = new ByteArrayOutputStream();
    TransportStreamRpuMover.copy(new ByteArrayInputStream(source), output);
    return output.toByteArray();
  }
}
