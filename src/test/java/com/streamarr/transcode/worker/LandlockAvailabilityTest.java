package com.streamarr.transcode.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.streamarr.transcode.worker.LandlockAvailability.Available;
import com.streamarr.transcode.worker.LandlockAvailability.Unavailable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import org.assertj.core.api.InstanceOfAssertFactories;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@Tag("UnitTest")
@DisplayName("Landlock Availability Tests")
class LandlockAvailabilityTest {

  @TempDir Path tempDir;

  @Test
  @DisplayName("Should report the ABI version when landlock-exec prints one")
  void shouldReportTheAbiVersionWhenLandlockExecPrintsOne() throws Exception {
    var helper = helper("[ \"$1\" = --abi ] || exit 99\necho 4\n");

    assertThat(LandlockAvailability.check(helper.toString())).isEqualTo(new Available(4));
  }

  @Test
  @DisplayName("Should ask for a newer kernel when landlock-exec reports ENOSYS")
  void shouldAskForANewerKernelWhenLandlockExecReportsEnosys() throws Exception {
    var helper =
        helper(
            "echo 'landlock-exec: Landlock is unavailable: landlock_create_ruleset: ENOSYS:"
                + " Function not implemented' >&2\nexit 2\n");

    var availability = LandlockAvailability.check(helper.toString());

    assertThat(availability)
        .asInstanceOf(InstanceOfAssertFactories.type(Unavailable.class))
        .satisfies(
            unavailable -> {
              assertThat(unavailable.reason()).contains("the kernel offers no Landlock", "ENOSYS");
              assertThat(unavailable.action()).contains("Linux 5.13");
            });
  }

  @Test
  @DisplayName("Should ask for the lsm boot parameter when landlock-exec reports EOPNOTSUPP")
  void shouldAskForTheLsmBootParameterWhenLandlockExecReportsEopnotsupp() throws Exception {
    var helper =
        helper(
            "echo 'landlock-exec: Landlock is unavailable: landlock_create_ruleset: EOPNOTSUPP:"
                + " Operation not supported' >&2\nexit 2\n");

    var availability = LandlockAvailability.check(helper.toString());

    assertThat(availability)
        .asInstanceOf(InstanceOfAssertFactories.type(Unavailable.class))
        .satisfies(
            unavailable -> {
              assertThat(unavailable.reason()).contains("not enabled", "EOPNOTSUPP");
              assertThat(unavailable.action()).contains("lsm=", "/sys/kernel/security/lsm");
            });
  }

  @Test
  @DisplayName("Should name the seccomp profile when landlock-exec reports another refusal")
  void shouldNameTheSeccompProfileWhenLandlockExecReportsAnotherRefusal() throws Exception {
    var helper =
        helper(
            "echo 'landlock-exec: Landlock is unavailable: landlock_create_ruleset: EPERM:"
                + " Operation not permitted' >&2\nexit 2\n");

    var availability = LandlockAvailability.check(helper.toString());

    assertThat(availability)
        .asInstanceOf(InstanceOfAssertFactories.type(Unavailable.class))
        .satisfies(
            unavailable -> {
              assertThat(unavailable.reason()).contains("EPERM");
              assertThat(unavailable.action()).contains("seccomp", "landlock_create_ruleset");
            });
  }

  @Test
  @DisplayName("Should name the configured path when landlock-exec is missing")
  void shouldNameTheConfiguredPathWhenLandlockExecIsMissing() throws Exception {
    var missing = tempDir.resolve("missing/landlock-exec");

    var availability = LandlockAvailability.check(missing.toString());

    assertThat(availability)
        .asInstanceOf(InstanceOfAssertFactories.type(Unavailable.class))
        .satisfies(
            unavailable -> {
              assertThat(unavailable.reason()).contains(missing.toString());
              assertThat(unavailable.action()).contains("TRANSCODE_WORKER_LANDLOCK_EXEC_PATH");
            });
  }

  @Test
  @DisplayName("Should refuse the report when landlock-exec prints no version")
  void shouldRefuseTheReportWhenLandlockExecPrintsNoVersion() throws Exception {
    var helper = helper("echo ready\n");

    var availability = LandlockAvailability.check(helper.toString());

    assertThat(availability)
        .asInstanceOf(InstanceOfAssertFactories.type(Unavailable.class))
        .extracting(Unavailable::reason)
        .asString()
        .contains("reported no Landlock ABI version", "ready");
  }

  @Test
  @DisplayName("Should report the exit code when landlock-exec fails in another way")
  void shouldReportTheExitCodeWhenLandlockExecFailsInAnotherWay() throws Exception {
    var helper = helper("echo 'segmentation fault' >&2\nexit 139\n");

    var availability = LandlockAvailability.check(helper.toString());

    assertThat(availability)
        .asInstanceOf(InstanceOfAssertFactories.type(Unavailable.class))
        .extracting(Unavailable::reason)
        .asString()
        .contains("139", "segmentation fault");
  }

  private Path helper(String body) throws Exception {
    var helper = tempDir.resolve("landlock-exec");
    Files.writeString(helper, "#!/bin/sh\n" + body);
    Files.setPosixFilePermissions(helper, PosixFilePermissions.fromString("rwx------"));
    return helper;
  }
}
