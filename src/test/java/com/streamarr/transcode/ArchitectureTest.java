package com.streamarr.transcode;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameMatching;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("UnitTest")
@DisplayName("Architecture Tests")
class ArchitectureTest {

  private static final JavaClasses WORKER_CLASSES =
      new ClassFileImporter()
          .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
          .importPackages("com.streamarr.transcode");

  @Test
  @DisplayName("Should keep the engine independent when other worker packages change")
  void shouldKeepTheEngineIndependentWhenOtherWorkerPackagesChange() {
    noClasses()
        .that()
        .resideInAPackage("com.streamarr.transcode.engine..")
        .should()
        .dependOnClassesThat()
        .resideInAnyPackage(
            "com.streamarr.transcode.worker..",
            "com.streamarr.transcode.probe..",
            "com.streamarr.transcode.protocol..")
        .check(WORKER_CLASSES);
  }

  @Test
  @DisplayName("Should keep Spring out of every class when it is not one of the Boot classes")
  void shouldKeepSpringOutOfEveryClassWhenItIsNotOneOfTheBootClasses() {
    noClasses()
        .that()
        .doNotHaveSimpleName("TranscodeWorkerApplication")
        .and()
        .doNotHaveSimpleName("WorkerActuatorConfiguration")
        .and()
        .doNotHaveSimpleName("SessionAcceptanceTimeoutFailureAnalyzer")
        .and()
        .doNotHaveSimpleName("LandlockUnavailableFailureAnalyzer")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework..")
        .check(WORKER_CLASSES);
  }

  // A media source opened by name follows a soft link swapped in after the worker's check unless
  // MediaSourceChannel opens it.
  @Test
  @DisplayName(
      "Should open a file only through MediaSourceChannel when a class reads a source itself")
  void shouldOpenAFileOnlyThroughMediaSourceChannelWhenAClassReadsASourceItself() {
    noClasses()
        .that()
        .doNotHaveSimpleName("MediaSourceChannel")
        .should()
        .callCodeUnitWhere(
            target(owner(type(FileChannel.class)))
                .and(target(name("open")))
                .or(
                    target(owner(type(Files.class)))
                        .and(target(nameMatching("newByteChannel|newInputStream")))))
        .check(WORKER_CLASSES);
  }
}
