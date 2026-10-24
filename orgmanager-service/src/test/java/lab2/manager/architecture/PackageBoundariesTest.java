package lab2.manager.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.*;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PackageBoundariesTest {
    private static JavaClasses code;

    @BeforeAll
    static void importProductionClasses() {
        code =
                new ClassFileImporter()
                        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                        .importPackages("lab2.manager");
    }

    @Test
    void everyProductionClassHasAnOwningLayer() {
        classes()
                .that()
                .resideInAPackage("lab2.manager..")
                .should()
                .resideInAnyPackage(
                        "lab2.manager.config..",
                        "lab2.manager.web..",
                        "lab2.manager.application..",
                        "lab2.manager.infrastructure..",
                        "lab2.manager.generated..")
                .check(code);
    }

    @Test
    void applicationDoesNotDependOnAdaptersOrFrameworks() {
        classes()
                .that()
                .resideInAPackage("lab2.manager.application..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "lab2.manager.application..",
                        "lab2.manager.generated.model..",
                        "lab2.manager.generated.upstream.model..")
                .check(code);
        noClasses()
                .that()
                .resideInAPackage("lab2.manager.application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("java.sql..")
                .check(code);
    }

    @Test
    void infrastructureDoesNotDependOnWebOrConfiguration() {
        noClasses()
                .that()
                .resideInAPackage("lab2.manager.infrastructure..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("lab2.manager.web..", "lab2.manager.config..")
                .check(code);
    }

    @Test
    void webUsesApplicationInterfacesInsteadOfInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("lab2.manager.web..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("lab2.manager.infrastructure..")
                .check(code);
    }
}
