package com.nexora.intelligence.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/** Hexagonal boundaries and independence from other Nexora services and from concrete AI providers. */
class ArchitectureTest {

    private static final String ROOT = "com.nexora.intelligence";

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages(ROOT);

    @Test
    void theDomainDependsOnlyOnTheJdkAndItself() {
        classes().that().resideInAPackage(ROOT + ".domain..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", ROOT + ".domain..")
                .because("the domain must not depend on Spring or any other framework")
                .check(CLASSES);
    }

    @Test
    void theApplicationLayerDependsOnlyOnTheDomainAndTheJdk() {
        classes().that().resideInAPackage(ROOT + ".application..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", ROOT + ".domain..",
                        ROOT + ".application..")
                .because("ports are framework-free and never reach adapters or infrastructure")
                .check(CLASSES);
    }

    @Test
    void noLayerDependsOnInfrastructure() {
        noClasses().that().resideInAnyPackage(ROOT + ".domain..", ROOT + ".application..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure..")
                .check(CLASSES);
    }

    @Test
    void noDependencyOnOtherNexoraServicesOrConcreteAiProviders() {
        noClasses().should().dependOnClassesThat().resideInAnyPackage(
                        "com.nexora.semantic..", "com.nexora.reports..", "com.nexora.discovery..",
                        "com.nexora.identity..",
                        "com.openai..", "dev.langchain4j..", "org.springframework.ai..", "io.github.ollama4j..",
                        "ai.djl..", "com.theokanning..", "io.github.sashirestela..")
                .because("Intelligence is independent of other services' code and of any inference provider")
                .check(CLASSES);
    }
}
