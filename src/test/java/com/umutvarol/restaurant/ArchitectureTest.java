package com.umutvarol.restaurant;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import jakarta.persistence.Entity;
import org.springframework.data.repository.Repository;
import org.springframework.web.bind.annotation.RestController;

/** Guards the package-by-feature architecture against erosion. */
@AnalyzeClasses(packages = "com.umutvarol.restaurant", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    @ArchTest
    static final ArchRule featuresAreFreeOfCycles = slices()
            .matching("com.umutvarol.restaurant.(*)..")
            .should().beFreeOfCycles();

    @ArchTest
    static final ArchRule commonCodeDoesNotDependOnFeatures = noClasses()
            .that().resideInAPackage("..restaurant.common..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..restaurant.menu..", "..restaurant.order..", "..restaurant.table..", "..restaurant.coupon..",
                    "..restaurant.floor..", "..restaurant.analytics..", "..restaurant.realtime..",
                    "..restaurant.security..", "..restaurant.bootstrap..", "..restaurant.payment..", "..restaurant.guest..", "..restaurant.printing..", "..restaurant.inventory..", "..restaurant.channel..");

    @ArchTest
    static final ArchRule controllersTalkToServicesNotRepositories = noClasses()
            .that().areAnnotatedWith(RestController.class)
            .and().doNotHaveSimpleName("AuthController")
            .should().dependOnClassesThat().areAssignableTo(Repository.class);

    @ArchTest
    static final ArchRule controllersAreNotPublic = classes()
            .that().areAnnotatedWith(RestController.class)
            .should().notBePublic();

    @ArchTest
    static final ArchRule entitiesDoNotKnowAboutTheWebLayer = noClasses()
            .that().areAnnotatedWith(Entity.class)
            .should().dependOnClassesThat().resideInAnyPackage("org.springframework.web..", "..dto..");

    @ArchTest
    static final ArchRule nothingDependsOnTheBootstrapPackage = noClasses()
            .that().resideOutsideOfPackage("..restaurant.bootstrap..")
            .should().dependOnClassesThat().resideInAPackage("..restaurant.bootstrap..");
}
