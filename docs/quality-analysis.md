# SonarCloud analysis

CI analyzes `main` and pull requests from this repository after `./mvnw clean verify`.
Pull request builds check out the head commit, so tests, coverage and analysis use the same source.
The scanner uses the merged JaCoCo report from the complete unit and integration suites.
Both coverage inputs and the merged report must exist. A missing token, failed analysis,
or failed quality gate fails the required `build` check.

The project is `streamarr_streamarr-transcode-worker` in the `streamarr` organization.
Select GitHub Actions analysis in SonarCloud and grant this repository access to the
organization's `ORG_SONAR_TOKEN` Actions secret. Its token must have permission to analyze
the worker project. CI maps it to the scanner's `SONAR_TOKEN` environment variable.
Project settings and the scanner version live in `pom.xml`.

Fork and Dependabot pull requests run the full build without SonarCloud credentials.
Their code is analyzed after it reaches `main`. A manually dispatched CI run analyzes
only `main`.

To analyze a local checkout with an authorized `SONAR_TOKEN` in the environment:

```sh
./mvnw clean verify
.github/actions/sonar/analyze.sh
```

The script waits for the quality gate and preserves scanner failures as a nonzero exit.
Never put the token in a command argument, source file, or log.
