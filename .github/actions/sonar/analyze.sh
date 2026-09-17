#!/usr/bin/env bash
set -euo pipefail

: "${SONAR_TOKEN:?Grant this repository access to ORG_SONAR_TOKEN and map it to SONAR_TOKEN}"

for coverage in \
  target/jacoco-output/jacoco-unit-tests.exec \
  target/jacoco-output/jacoco-integration-tests.exec \
  target/site/jacoco-merged-test-coverage-report/jacoco.xml; do
  if [[ ! -s "$coverage" ]]; then
    printf 'Missing coverage: %s. Run ./mvnw clean verify before analysis.\n' "$coverage" >&2
    exit 1
  fi
done

./mvnw --batch-mode org.sonarsource.scanner.maven:sonar-maven-plugin:sonar \
  -Dsonar.qualitygate.wait=true
