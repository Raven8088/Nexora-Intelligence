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

    @Test
    void theInferenceAdapterIsOnlyReachedThroughItsPortAndConfiguration() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".infrastructure.inference..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure.inference..")
                .because("callers use the QuestionInterpreter port; the adapter is wired by its own configuration")
                .check(CLASSES);
    }

    @Test
    void theInferenceAdapterImplementsThePortWithoutReachingOtherAdapters() {
        classes().that().resideInAPackage(ROOT + ".infrastructure.inference..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax..", "org.slf4j..",
                        "org.springframework..", "tools.jackson..", ROOT + ".domain..",
                        ROOT + ".application.port.out..", ROOT + ".infrastructure.inference..")
                .check(CLASSES);
    }

    @Test
    void theSemanticAdapterIsOnlyReachedThroughItsPortsAndConfiguration() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".infrastructure.semantic..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure.semantic..")
                .because("callers use the SemanticCatalogClient and LogicalPlanClient ports")
                .check(CLASSES);
    }

    @Test
    void theSemanticAdapterImplementsItsPortsWithoutReachingOtherAdaptersOrSecurityInternals() {
        classes().that().resideInAPackage(ROOT + ".infrastructure.semantic..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax..", "org.slf4j..",
                        "org.springframework..", "tools.jackson..", ROOT + ".domain..",
                        ROOT + ".application.port.out..", ROOT + ".infrastructure.semantic..",
                        ROOT + ".infrastructure.serviceclient..")
                .because("token verification uses Spring Security's standard JWT support, not a JOSE library directly")
                .check(CLASSES);
    }

    @Test
    void theReportsAdapterIsOnlyReachedThroughItsPortAndConfiguration() {
        noClasses().that().resideOutsideOfPackage(ROOT + ".infrastructure.reports..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure.reports..")
                .because("callers use the ReportExecutionClient port")
                .check(CLASSES);
    }

    @Test
    void theReportsAdapterImplementsItsPortWithoutReachingOtherAdapters() {
        classes().that().resideInAPackage(ROOT + ".infrastructure.reports..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax..", "org.slf4j..",
                        "org.springframework..", "tools.jackson..", ROOT + ".domain..",
                        ROOT + ".application.port.out..", ROOT + ".infrastructure.reports..",
                        ROOT + ".infrastructure.serviceclient..")
                .check(CLASSES);
    }

    @Test
    void sharedServiceClientsKnowNoAdapterPortOrDomain() {
        classes().that().resideInAPackage(ROOT + ".infrastructure.serviceclient..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax..", "org.slf4j..",
                        "org.springframework..", "tools.jackson..", ROOT + ".infrastructure.serviceclient..")
                .because("token, JWT verification and bounded HTTP are shared, adapter-neutral infrastructure")
                .check(CLASSES);
    }

    @Test
    void resultsBuildOnRequestsAndOutcomesNotTheReverse() {
        noClasses().that().resideInAnyPackage(ROOT + ".domain.shared..", ROOT + ".domain.semantic..",
                        ROOT + ".domain.request..", ROOT + ".domain.outcome..", ROOT + ".domain.question..",
                        ROOT + ".domain.interpretation..", ROOT + ".domain.period..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.result..")
                .because("a result is the last step: it refers to plans and definitions, never the reverse")
                .check(CLASSES);
    }

    @Test
    void theQuestionApiIsAnInboundAdapterOverTheApplication() {
        classes().that().resideInAPackage(ROOT + ".infrastructure.web..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "javax..", "jakarta.servlet..",
                        "org.slf4j..", "org.springframework..", "tools.jackson..", ROOT + ".domain..",
                        ROOT + ".application..", ROOT + ".infrastructure.web..")
                .because("the API calls the application services; it never reaches the outbound adapters")
                .check(CLASSES);
        noClasses().that().resideOutsideOfPackage(ROOT + ".infrastructure.web..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".infrastructure.web..")
                .check(CLASSES);
    }

    @Test
    void thePlanningWiringOnlyUsesPortsAndApplicationServices() {
        classes().that().resideInAPackage(ROOT + ".infrastructure.planning..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", "org.springframework..",
                        ROOT + ".domain..", ROOT + ".application..", ROOT + ".infrastructure.planning..")
                .check(CLASSES);
    }

    @Test
    void theSemanticModelLayersFromCatalogToOutcome() {
        noClasses().that().resideInAnyPackage(ROOT + ".domain.shared..", ROOT + ".domain.semantic..")
                .should().dependOnClassesThat().resideInAnyPackage(ROOT + ".domain.request..",
                        ROOT + ".domain.outcome..")
                .because("the catalog describes what exists; requests and outcomes build on it, not the reverse")
                .check(CLASSES);
        noClasses().that().resideInAPackage(ROOT + ".domain.request..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".domain.outcome..")
                .check(CLASSES);
    }

    @Test
    void periodsAreSelfContainedCalendarLogic() {
        classes().that().resideInAPackage(ROOT + ".domain.period..")
                .should().onlyDependOnClassesThat().resideInAnyPackage("java..", ROOT + ".domain.period..")
                .because("period resolution is pure calendar arithmetic, reusable by any caller")
                .check(CLASSES);
    }
}
