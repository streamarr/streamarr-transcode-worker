package com.streamarr.transcode.engine;

import static com.streamarr.transcode.engine.EncodeFeature.DOLBY_VISION_TO_PQ;
import static com.streamarr.transcode.engine.EncodeFeature.HLG;
import static com.streamarr.transcode.engine.EncodeFeature.PQ;
import static com.streamarr.transcode.engine.EncodeFeature.TEN_BIT;
import static com.streamarr.transcode.engine.IsoBoxes.box;
import static com.streamarr.transcode.fixtures.FfmpegFilterHelpFixtures.GAP_FILL_FILTER_HELP;
import static com.streamarr.transcode.fixtures.FfmpegMuxerHelpFixtures.FRAGMENTED_MP4_MUXER_HELP;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

@Tag("UnitTest")
@DisplayName("Transcode Capability Service Encode Feature Tests")
class TranscodeCapabilityServiceEncodeFeatureTest {

  // Main 10 at level 3.0, 10-bit 4:2:0, with no parameter set arrays.
  private static final String MAIN_10_HVCC = "0102200000009000000000005AF000FCFDFAFA00000F00";
  // Main 8 at level 3.0, 8-bit 4:2:0, with no parameter set arrays.
  private static final String MAIN_HVCC = "0101600000009000000000005AF000FCFDF8F800000F00";
  // AV1 Main at level 2.0, 10-bit 4:2:0.
  private static final String TEN_BIT_AV1C = "81004C00";
  private static final int BT2020 = 9;
  private static final int BT709 = 1;
  private static final int PQ_TRANSFER = 16;
  private static final int HLG_TRANSFER = 18;
  private static final int FAILED_EXIT_CODE = 187;
  private static final String HEVC_NVENC_LISTING =
      "V....D hevc_nvenc           NVIDIA NVENC hevc encoder (codec hevc)\n";

  @Test
  @DisplayName(
      "Should list every encode feature of the software HEVC and AV1 encoders when each feature's"
          + " test encode writes its picture format and colour description")
  void shouldListEveryEncodeFeatureWhenEachFeaturesTestEncodeWritesItsPictureFormatAndColour() {
    var service = serviceWhoseTestEncodesWrite(TestEncodeOutput::faithful);

    service.detectCapabilities();

    assertThat(featuresByEncoder(service))
        .containsExactlyInAnyOrderEntriesOf(
            Map.of(
                "libx264", Set.of(),
                "libx265", Set.of(EncodeFeature.values()),
                "libsvtav1", Set.of(EncodeFeature.values())));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("failedFeatureTests")
  @DisplayName("Should list no encode feature whose test encode fails or writes another output")
  void shouldListNoEncodeFeatureWhoseTestEncodeFailsOrWritesAnotherOutput(
      String failure, EncodeFeature failedFeature, TestEncodeOutput failedOutput) {
    var service =
        serviceWhoseTestEncodesWrite(
            test ->
                test.feature() == failedFeature ? failedOutput : TestEncodeOutput.faithful(test));

    service.detectCapabilities();

    var everyOtherFeature =
        Stream.of(EncodeFeature.values()).filter(feature -> feature != failedFeature).toList();
    assertThat(featuresByEncoder(service))
        .containsEntry("libx265", Set.copyOf(everyOtherFeature))
        .containsEntry("libsvtav1", Set.copyOf(everyOtherFeature));
  }

  static Stream<Arguments> failedFeatureTests() {
    return Stream.of(
        Arguments.of(
            "the 10-bit test writes 8 bits",
            EncodeFeature.TEN_BIT,
            TestEncodeOutput.of(eightBitSegment(new byte[0]))),
        Arguments.of(
            "the PQ test declares the BT.709 transfer",
            EncodeFeature.PQ,
            TestEncodeOutput.of(tenBitHevcSegment(colr(BT2020, BT709, BT2020)))),
        Arguments.of(
            "the PQ test declares BT.709 primaries",
            EncodeFeature.PQ,
            TestEncodeOutput.of(tenBitHevcSegment(colr(BT709, PQ_TRANSFER, BT2020)))),
        Arguments.of(
            "the HLG test writes no colr box",
            EncodeFeature.HLG,
            TestEncodeOutput.of(tenBitHevcSegment(new byte[0]))),
        Arguments.of(
            "the HLG test declares the PQ transfer",
            EncodeFeature.HLG,
            TestEncodeOutput.of(tenBitHevcSegment(colr(BT2020, PQ_TRANSFER, BT2020)))),
        Arguments.of(
            "the Dolby Vision reshape test exits with an error",
            EncodeFeature.DOLBY_VISION_TO_PQ,
            new TestEncodeOutput(
                tenBitHevcSegment(colr(BT2020, PQ_TRANSFER, BT2020)), FAILED_EXIT_CODE)),
        Arguments.of(
            "the Dolby Vision reshape test writes nothing",
            EncodeFeature.DOLBY_VISION_TO_PQ,
            TestEncodeOutput.of(new byte[0])),
        Arguments.of(
            "the 10-bit test writes a truncated moov",
            EncodeFeature.TEN_BIT,
            TestEncodeOutput.of(truncatedMoov())));
  }

  @Test
  @DisplayName(
      "Should run the Dolby Vision reshape test on an owner-only copy of the worker's profile 5"
          + " test frame and delete the copy when detection ends")
  void shouldRunTheDolbyVisionReshapeTestOnAnOwnerOnlyCopyOfTheTestFrameWhenDetectionRuns()
      throws IOException {
    var testFrame = testFrame();
    var inputsOfTheReshapeTests = new ArrayList<Path>();
    var inputBytes = new ArrayList<byte[]>();
    var inputPermissions = new ArrayList<Set<PosixFilePermission>>();
    var service =
        serviceWhoseTestEncodesWrite(
            test -> {
              if (test.feature() == EncodeFeature.DOLBY_VISION_TO_PQ) {
                inputsOfTheReshapeTests.add(test.input().orElseThrow());
                inputBytes.add(readAllBytes(test.input().orElseThrow()));
                inputPermissions.add(posixPermissions(test.input().orElseThrow()));
              }

              return TestEncodeOutput.faithful(test);
            });

    service.detectCapabilities();

    assertThat(inputBytes).hasSize(2).allSatisfy(bytes -> assertThat(bytes).isEqualTo(testFrame));
    assertThat(inputPermissions)
        .hasSize(2)
        .allSatisfy(
            permissions ->
                assertThat(permissions).isEqualTo(PosixFilePermissions.fromString("rw-------")));
    assertThat(inputsOfTheReshapeTests).allSatisfy(input -> assertThat(input).doesNotExist());
  }

  @Test
  @DisplayName(
      "Should keep verified encode features when the Dolby Vision sample cannot be deleted")
  void shouldKeepVerifiedEncodeFeaturesWhenTheDolbyVisionSampleCannotBeDeleted()
      throws IOException {
    var blockedSamples = new HashSet<Path>();
    var service =
        serviceWhoseTestEncodesWrite(
            test -> {
              if (test.feature() != DOLBY_VISION_TO_PQ) {
                return TestEncodeOutput.faithful(test);
              }

              var input = test.input().orElseThrow();
              if (blockedSamples.add(input)) {
                preventSampleCleanup(input);
              }

              return new TestEncodeOutput(new byte[0], FAILED_EXIT_CODE);
            });

    try {
      service.detectCapabilities();

      var verifiedFeatures = Set.of(TEN_BIT, PQ, HLG);
      assertThat(featuresByEncoder(service))
          .containsEntry("libx265", verifiedFeatures)
          .containsEntry("libsvtav1", verifiedFeatures);
      assertThat(blockedSamples)
          .isNotEmpty()
          .allSatisfy(input -> assertThat(input.resolve("retained")).isRegularFile());
    } finally {
      for (var input : blockedSamples) {
        Files.deleteIfExists(input.resolve("retained"));
        Files.deleteIfExists(input);
      }
    }
  }

  @Test
  @DisplayName(
      "Should list the software HEVC encoder's encode features when the worker offers a hardware"
          + " HEVC encoder, and offer them once that encoder is withdrawn")
  void shouldListTheSoftwareEncodersFeaturesWhenTheWorkerOffersAHardwareEncoderOfItsFamily() {
    var service =
        new TranscodeCapabilityService(
            "ffmpeg",
            command ->
                command.command().contains("-encoders")
                    ? new FakeProcess(HEVC_NVENC_LISTING, 0)
                    : resolve(command, test -> TestEncodeOutput.faithful(test).process()));

    service.detectCapabilities();
    var offeredWithTheHardwareEncoder = featuresByEncoder(service);
    service.withdraw(service.resolveEncoder("hevc").orElseThrow());

    assertThat(offeredWithTheHardwareEncoder)
        .containsEntry("hevc_nvenc", Set.of())
        .doesNotContainKey("libx265");
    assertThat(service.softwareEncoders())
        .filteredOn(encoder -> encoder.backend().encoder().equals("libx265"))
        .singleElement()
        .extracting(WorkerVerifiedEncoder::encodeFeatures)
        .isEqualTo(Set.of(EncodeFeature.values()));
    assertThat(featuresByEncoder(service)).containsEntry("libx265", Set.of(EncodeFeature.values()));
  }

  @Test
  @DisplayName(
      "Should list no encode feature and stop the test encode when it outlasts its deadline")
  void shouldListNoEncodeFeatureAndStopTheTestEncodeWhenItOutlastsItsDeadline() {
    var hungTests = new ArrayList<HungTestEncode>();
    var service =
        serviceWhoseFeatureTestsRun(
            test -> {
              if (test.feature() != EncodeFeature.PQ) {
                return TestEncodeOutput.faithful(test).process();
              }

              var hung = new HungTestEncode(false);
              hungTests.add(hung);
              return hung;
            });

    service.detectCapabilities();

    assertThat(featuresByEncoder(service))
        .containsEntry("libx265", Set.of(TEN_BIT, HLG, DOLBY_VISION_TO_PQ))
        .containsEntry("libsvtav1", Set.of(TEN_BIT, HLG, DOLBY_VISION_TO_PQ));
    assertThat(hungTests).hasSize(2).allSatisfy(hung -> assertThat(hung.isAlive()).isFalse());
  }

  @Test
  @DisplayName(
      "Should start no further test encode and restore the interrupt when detection is interrupted"
          + " during a feature test")
  void shouldStartNoFurtherTestEncodeAndRestoreTheInterruptWhenDetectionIsInterrupted() {
    var startedTests = new ArrayList<FeatureTest>();
    var service =
        serviceWhoseFeatureTestsRun(
            test -> {
              startedTests.add(test);
              return new HungTestEncode(true);
            });

    try {
      service.detectCapabilities();

      assertThat(startedTests).hasSize(1);
      assertThat(featuresByEncoder(service))
          .containsEntry("libx265", Set.of())
          .containsEntry("libsvtav1", Set.of());
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static TranscodeCapabilityService serviceWhoseTestEncodesWrite(
      Function<FeatureTest, TestEncodeOutput> outputs) {
    return serviceWhoseFeatureTestsRun(test -> outputs.apply(test).process());
  }

  private static TranscodeCapabilityService serviceWhoseFeatureTestsRun(
      Function<FeatureTest, Process> featureTests) {
    return new TranscodeCapabilityService("ffmpeg", command -> resolve(command, featureTests));
  }

  private static Map<String, Set<EncodeFeature>> featuresByEncoder(
      TranscodeCapabilityService service) {
    var features = new HashMap<String, Set<EncodeFeature>>();
    service
        .workerVerifiedEncoders()
        .forEach(encoder -> features.put(encoder.backend().encoder(), encoder.encodeFeatures()));
    return features;
  }

  private static Process resolve(
      ProcessBuilder command, Function<FeatureTest, Process> featureTests) {
    var arguments = command.command();
    if (arguments.contains("muxer=mp4")) {
      return new FakeProcess(FRAGMENTED_MP4_MUXER_HELP, 0);
    }

    if (arguments.contains("filter=agapfill")) {
      return new FakeProcess(GAP_FILL_FILTER_HELP, 0);
    }

    // The version check, and an encoder list and accelerator list that name no hardware.
    if (!arguments.contains("-c:v")) {
      return new FakeProcess("", 0);
    }

    var encoder = arguments.get(arguments.indexOf("-c:v") + 1);
    var filters = arguments.get(arguments.indexOf("-vf") + 1);
    return FeatureTest.of(encoder, filters, arguments)
        .map(featureTests)
        .orElseGet(() -> new FakeProcess("", 0));
  }

  // A feature test encode, recognized by the filters that its command runs.
  record FeatureTest(String encoder, EncodeFeature feature, Optional<Path> input) {

    private static Optional<FeatureTest> of(
        String encoder, String filters, List<String> arguments) {
      var input = Path.of(arguments.get(arguments.indexOf("-i") + 1));
      if (filters.contains("tonemapx")) {
        return Optional.of(
            new FeatureTest(encoder, EncodeFeature.DOLBY_VISION_TO_PQ, Optional.of(input)));
      }

      if (filters.contains("arib-std-b67")) {
        return Optional.of(new FeatureTest(encoder, EncodeFeature.HLG, Optional.empty()));
      }

      if (filters.contains("smpte2084")) {
        return Optional.of(new FeatureTest(encoder, EncodeFeature.PQ, Optional.empty()));
      }

      if (filters.equals("format=yuv420p10le")) {
        return Optional.of(new FeatureTest(encoder, EncodeFeature.TEN_BIT, Optional.empty()));
      }

      return Optional.empty();
    }
  }

  record TestEncodeOutput(byte[] bytes, int exitCode) {

    private static TestEncodeOutput of(byte[] bytes) {
      return new TestEncodeOutput(bytes, 0);
    }

    private Process process() {
      return new FakeProcess(bytes, exitCode);
    }

    // The initialization segment that the encoder writes when it writes the feature's output.
    private static TestEncodeOutput faithful(FeatureTest test) {
      var colr =
          switch (test.feature()) {
            case TEN_BIT -> new byte[0];
            case PQ, DOLBY_VISION_TO_PQ -> colr(BT2020, PQ_TRANSFER, BT2020);
            case HLG -> colr(BT2020, HLG_TRANSFER, BT2020);
          };
      return of(
          test.encoder().equals("libsvtav1") ? tenBitAv1Segment(colr) : tenBitHevcSegment(colr));
    }
  }

  // A test encode that writes nothing and never exits until it is killed; or, when interrupted, one
  // whose wait the detection's interrupt ends.
  private static final class HungTestEncode extends FakeProcess {

    private final boolean interrupted;
    private boolean alive = true;

    private HungTestEncode(boolean interrupted) {
      super("", 0);
      this.interrupted = interrupted;
    }

    @Override
    public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
      if (interrupted) {
        throw new InterruptedException("Detection interrupted");
      }

      return !alive;
    }

    @Override
    public boolean isAlive() {
      return alive;
    }

    @Override
    public void destroy() {
      alive = false;
    }
  }

  private static byte[] tenBitHevcSegment(byte[] colr) {
    return HevcCopies.initializationSegment(
        box("hvc1", new byte[78], box("hvcC", HexFormat.of().parseHex(MAIN_10_HVCC)), colr));
  }

  private static byte[] tenBitAv1Segment(byte[] colr) {
    return HevcCopies.initializationSegment(
        box("av01", new byte[78], box("av1C", HexFormat.of().parseHex(TEN_BIT_AV1C)), colr));
  }

  // An 8-bit 4:2:0 initialization segment, which neither encoder's 10-bit test may write.
  private static byte[] eightBitSegment(byte[] colr) {
    return HevcCopies.initializationSegment(
        box("hvc1", new byte[78], box("hvcC", HexFormat.of().parseHex(MAIN_HVCC)), colr));
  }

  // An ftyp and a moov header that claims more bytes than the output holds.
  private static byte[] truncatedMoov() {
    return IsoBoxes.concat(IsoBoxes.ftyp(), IsoBoxes.header(4096, "moov"));
  }

  // An nclx colr box with the primaries, transfer and matrix, and limited range.
  private static byte[] colr(int primaries, int transfer, int matrix) {
    return box(
        "colr",
        IsoBoxes.fourcc("nclx"),
        IsoBoxes.u16(primaries),
        IsoBoxes.u16(transfer),
        IsoBoxes.u16(matrix),
        new byte[] {0});
  }

  private static byte[] testFrame() throws IOException {
    try (var resource =
        TranscodeCapabilityService.class.getResourceAsStream("dolby-vision-5-test-frame.mp4")) {
      assertThat(resource).as("the Dolby Vision test frame").isNotNull();
      return resource.readAllBytes();
    }
  }

  private static void preventSampleCleanup(Path input) {
    try {
      Files.delete(input);
      Files.createDirectory(input);
      Files.createFile(input.resolve("retained"));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static Set<PosixFilePermission> posixPermissions(Path path) {
    try {
      return Files.getPosixFilePermissions(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static byte[] readAllBytes(Path path) {
    try {
      return Files.readAllBytes(path);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
