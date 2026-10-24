package lab2.organizations.architecture;

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
                        .importPackages("lab2.organizations");
    }

    @Test
    void everyProductionClassHasAnOwningLayer() {
        classes()
                .that()
                .resideInAPackage("lab2.organizations..")
                .should()
                .resideInAnyPackage(
                        "lab2.organizations.config..",
                        "lab2.organizations.web..",
                        "lab2.organizations.application..",
                        "lab2.organizations.infrastructure..",
                        "lab2.organizations.generated..")
                .check(code);
    }

    @Test
    void applicationDoesNotDependOnAdaptersOrFrameworks() {
        classes()
                .that()
                .resideInAPackage("lab2.organizations.application..")
                .should()
                .onlyDependOnClassesThat()
                .resideInAnyPackage(
                        "java..",
                        "lab2.organizations.application..",
                        "lab2.organizations.generated.model..")
                .check(code);
        noClasses()
                .that()
                .resideInAPackage("lab2.organizations.application..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("java.sql..")
                .check(code);
    }

    @Test
    void infrastructureDoesNotDependOnWebOrConfiguration() {
        noClasses()
                .that()
                .resideInAPackage("lab2.organizations.infrastructure..")
                .should()
                .dependOnClassesThat()
                .resideInAnyPackage("lab2.organizations.web..", "lab2.organizations.config..")
                .check(code);
    }

    @Test
    void webUsesApplicationInterfacesInsteadOfInfrastructure() {
        noClasses()
                .that()
                .resideInAPackage("lab2.organizations.web..")
                .should()
                .dependOnClassesThat()
                .resideInAPackage("lab2.organizations.infrastructure..")
                .check(code);
    }
}
