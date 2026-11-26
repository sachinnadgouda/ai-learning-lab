package dev.demo.traffic.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "dev.demo.traffic", importOptions = ImportOption.DoNotIncludeTests.class)
class HexagonalArchitectureTest {
    @ArchTest static final ArchRule domainIsPure = noClasses().that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage("..application..", "..adapter..", "..config..",
                    "org.springframework..", "tools.jackson..", "com.fasterxml..", "io.micrometer..", "jakarta..",
                    "org.slf4j..", "redis..", "io.lettuce..");

    @ArchTest static final ArchRule applicationDoesNotKnowAdapters = noClasses().that().resideInAPackage("..application..")
            .should().dependOnClassesThat().resideInAnyPackage("..adapter..", "..config..", "org.springframework..",
                    "tools.jackson..", "com.fasterxml..", "io.micrometer..", "jakarta..", "io.lettuce..");

    @ArchTest static final ArchRule adaptersDoNotDependOnEachOther = noClasses().that().resideInAPackage("..adapter.out..")
            .should().dependOnClassesThat().resideInAPackage("..adapter.in..");
}
