package com.streamarr.transcode.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import javax.xml.xpath.XPathFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.yaml.snakeyaml.Yaml;

@Tag("UnitTest")
@DisplayName("Mutation Testing Workflow Tests")
class MutationTestingWorkflowTest {

  private static final String WORKFLOW = ".github/workflows/mutation-testing.yml";
  private static final String SHARD_JOB = "mutation";
  private static final String REPORT_JOB = "report";
  private static final String PROFILE = "mutation-testing";
  private static final String PIT_STEP = "Mutate the production classes under the unit tests";
  private static final String PITEST_CONFIGURATION =
      "/project/profiles/profile[id='"
          + PROFILE
          + "']/build/plugins/plugin[artifactId='pitest-maven']/configuration";
  private static final Pattern PINNED_ACTION =
      Pattern.compile("[\\w.-]+/[\\w.-]+(/[\\w.-]+)*@[0-9a-f]{40}");

  @Test
  @DisplayName("Should run only on the nightly schedule or a manual dispatch when triggered")
  void shouldRunOnlyOnTheNightlyScheduleOrAManualDispatchWhenTriggered() throws IOException {
    var triggers = map(yaml(WORKFLOW).get(true));

    assertThat(triggers).containsOnlyKeys("schedule", "workflow_dispatch");
    assertThat(list(triggers.get("schedule"))).hasSize(1);
  }

  @Test
  @DisplayName("Should read only the repository and its earlier runs when reporting mutants")
  void shouldReadOnlyTheRepositoryAndItsEarlierRunsWhenReportingMutants() throws IOException {
    var workflow = yaml(WORKFLOW);

    assertThat(map(workflow.get("jobs"))).containsOnlyKeys(SHARD_JOB, REPORT_JOB);
    assertThat(workflow).containsEntry("permissions", Map.of());
    assertThat(map(job(SHARD_JOB).get("permissions"))).containsOnly(entry("contents", "read"));
    assertThat(map(job(REPORT_JOB).get("permissions")))
        .containsOnly(entry("contents", "read"), entry("actions", "read"));
    assertThat(Stream.concat(steps(job(SHARD_JOB)).stream(), steps(job(REPORT_JOB)).stream()))
        .allSatisfy(
            step -> {
              if (step.get("uses") instanceof String action) {
                assertThat(action).matches(PINNED_ACTION);
              }
            });
    assertThat(workflow.toString()).doesNotContain("secrets.");
  }

  @Test
  @DisplayName("Should mutate every class once when the run is split into shards")
  void shouldMutateEveryClassOnceWhenTheRunIsSplitIntoShards() throws IOException {
    var shards = map(yaml(WORKFLOW).get("env")).get("SHARDS");
    var strategy = map(job(SHARD_JOB).get("strategy"));

    assertThat(map(strategy.get("matrix")).get("shard"))
        .isEqualTo(IntStream.range(0, (Integer) shards).boxed().toList());
    assertThat(stepNamed(steps(job(SHARD_JOB)), PIT_STEP).get("run").toString())
        .contains("-P" + PROFILE, "\"-DtargetClasses=$classes\"", "mutationCoverage");
  }

  @Test
  @DisplayName("Should stop a shard before PIT runs when its classes cannot be listed")
  void shouldStopAShardBeforePitRunsWhenItsClassesCannotBeListed() throws IOException {
    var run = stepNamed(steps(job(SHARD_JOB)), PIT_STEP).get("run").toString();

    assertThat(run.lines().findFirst())
        .contains(
            "classes=\"$(src/test/mutation-report/shard-classes.sh \"$SHARD\" \"$SHARDS\")\"");
  }

  @Test
  @DisplayName("Should report what the other shards wrote when one shard fails")
  void shouldReportWhatTheOtherShardsWroteWhenOneShardFails() throws IOException {
    var shard = job(SHARD_JOB);
    var keep = stepNamed(steps(shard), "Keep the HTML report");

    assertThat(map(shard.get("strategy"))).containsEntry("fail-fast", false);
    assertThat(stepNamed(steps(shard), PIT_STEP)).doesNotContainKey("continue-on-error");
    assertThat(keep).containsEntry("if", "always()");
    assertThat(map(keep.get("with")))
        .containsEntry("name", "mutation-report-${{ matrix.shard }}")
        .containsEntry("path", "target/pit-reports");
    assertThat(job(REPORT_JOB))
        .containsEntry("needs", SHARD_JOB)
        .containsEntry("if", "${{ !cancelled() }}");
    assertThat(map(stepNamed(steps(job(REPORT_JOB)), "Receive every shard's report").get("with")))
        .containsEntry("pattern", "mutation-report-*")
        .containsEntry("path", "${{ runner.temp }}/reports");
    assertThat(summarizeStep())
        .contains("--report \"$RUNNER_TEMP/reports/mutation-report-$shard/mutations.xml\"");
  }

  @Test
  @DisplayName("Should compare with the summary the last run kept when a run fetches it")
  void shouldCompareWithTheSummaryTheLastRunKeptWhenARunFetchesIt() throws IOException {
    var steps = steps(job(REPORT_JOB));
    var kept = map(stepNamed(steps, "Keep the summary for the next night").get("with"));

    assertThat(kept)
        .containsEntry("name", "mutation-summary")
        .containsEntry("path", "${{ runner.temp }}/summary/summary.json");
    assertThat(Files.readString(Path.of("src/test/mutation-report/fetch-previous.sh")))
        .contains("--name mutation-summary");
    assertThat(stepNamed(steps, "Fetch the previous summary from main").get("run").toString())
        .contains("fetch-previous.sh \"$RUNNER_TEMP/previous\"");
    assertThat(summarizeStep())
        .contains(
            "--previous \"$RUNNER_TEMP/previous/summary.json\"",
            "--write \"$RUNNER_TEMP/summary/summary.json\"");
  }

  @Test
  @DisplayName("Should leave PIT out of every other build when the profile is not named")
  void shouldLeavePitOutOfEveryOtherBuildWhenTheProfileIsNotNamed() throws Exception {
    var pom = pom();
    var profile = profile(pom);

    assertThat(profile).isNotNull();
    assertThat(nodes(profile, "activation").getLength()).isZero();
    assertThat(nodes(pom, PITEST_CONFIGURATION + "/targetClasses").getLength()).isZero();
    assertThat(
            nodes(pom, "/project/build//plugin[artifactId='pitest-maven']").getLength()
                + nodes(pom, "/project/reporting//plugin[artifactId='pitest-maven']").getLength())
        .isZero();
    try (var workflows = Files.list(Path.of(".github/workflows"))) {
      for (var workflow :
          workflows.filter(path -> !path.endsWith("mutation-testing.yml")).toList()) {
        assertThat(Files.readString(workflow)).as(workflow.toString()).doesNotContain(PROFILE);
      }
    }
  }

  @Test
  @DisplayName("Should never fail the build on the score when mutants survive")
  void shouldNeverFailTheBuildOnTheScoreWhenMutantsSurvive() throws Exception {
    var configuration = node(pom(), PITEST_CONFIGURATION);

    assertThat(configuration).isNotNull();
    for (var threshold :
        List.of(
            "mutationThreshold", "coverageThreshold", "testStrengthThreshold", "maxSurviving")) {
      assertThat(nodes(configuration, threshold).getLength()).as(threshold).isZero();
    }
  }

  @Test
  @DisplayName("Should run only unit tests under PIT when real FFmpeg tests share their packages")
  void shouldRunOnlyUnitTestsUnderPitWhenRealFfmpegTestsShareTheirPackages() throws Exception {
    var configuration = node(pom(), PITEST_CONFIGURATION);

    assertThat(texts(configuration, "excludedGroups/param"))
        .containsExactlyInAnyOrder("SmokeTest", "IntegrationTest", "ImageTest");
    assertThat(texts(configuration, "targetTests/param"))
        .containsExactly(
            "com.streamarr.transcode.engine.*Test",
            "com.streamarr.transcode.probe.*Test",
            "com.streamarr.transcode.worker.*Test");
  }

  private static Document pom() throws Exception {
    var factory = DocumentBuilderFactory.newInstance();
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
    return factory.newDocumentBuilder().parse(Path.of("pom.xml").toFile());
  }

  private static Node profile(Document pom) throws XPathExpressionException {
    return node(pom, "/project/profiles/profile[id='" + PROFILE + "']");
  }

  private static Node node(Node context, String expression) throws XPathExpressionException {
    return (Node)
        XPathFactory.newInstance().newXPath().evaluate(expression, context, XPathConstants.NODE);
  }

  private static List<String> texts(Node context, String expression)
      throws XPathExpressionException {
    var found = nodes(context, expression);
    return IntStream.range(0, found.getLength())
        .mapToObj(index -> found.item(index).getTextContent())
        .toList();
  }

  private static NodeList nodes(Node context, String expression) throws XPathExpressionException {
    return (NodeList)
        XPathFactory.newInstance().newXPath().evaluate(expression, context, XPathConstants.NODESET);
  }

  private static Map<String, Object> job(String name) throws IOException {
    return map(map(yaml(WORKFLOW).get("jobs")).get(name));
  }

  private static String summarizeStep() throws IOException {
    return stepNamed(steps(job(REPORT_JOB)), "Summarize the mutants no test detected")
        .get("run")
        .toString();
  }

  private static Map<String, Object> yaml(String path) throws IOException {
    return new Yaml().load(Files.readString(Path.of(path)));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> steps(Map<String, Object> job) {
    return (List<Map<String, Object>>) job.get("steps");
  }

  private static Map<String, Object> stepNamed(List<Map<String, Object>> steps, String name) {
    return steps.stream().filter(step -> name.equals(step.get("name"))).findFirst().orElseThrow();
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return value == null ? Map.of() : (Map<String, Object>) value;
  }

  private static List<?> list(Object value) {
    return value == null ? List.of() : (List<?>) value;
  }
}
