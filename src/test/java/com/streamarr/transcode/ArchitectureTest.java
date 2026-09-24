package com.streamarr.transcode;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
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
  @DisplayName("Should keep Spring out of every class when it is not one of the Boot classes")
  void shouldKeepSpringOutOfEveryClassWhenItIsNotOneOfTheBootClasses() {
    noClasses()
        .that()
        .doNotHaveSimpleName("TranscodeWorkerApplication")
        .and()
        .doNotHaveSimpleName("WorkerActuatorConfiguration")
        .should()
        .dependOnClassesThat()
        .resideInAPackage("org.springframework..")
        .check(WORKER_CLASSES);
  }
}
