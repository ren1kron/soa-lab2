plugins {
    java
    war
    id("com.diffplug.spotless") version "8.10.4"
}

group = "lab2"

version = "1.0.0"

repositories { mavenCentral() }

java { toolchain { languageVersion.set(JavaLanguageVersion.of(17)) } }

val lombok = "org.projectlombok:lombok:1.18.48"

sourceSets.configureEach {
    project.dependencies.add(compileOnlyConfigurationName, lombok)
    project.dependencies.add(annotationProcessorConfigurationName, lombok)
}

spotless {
    java {
        target("src/*/java/**/*.java")
        googleJavaFormat("1.24.0").aosp()
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktfmt("0.54").kotlinlangStyle()
    }
}

val generator by configurations.creating

dependencies {
    generator("org.openapitools:openapi-generator-cli:7.19.0")
    implementation(platform("org.springframework:spring-framework-bom:6.2.11"))
    implementation("org.springframework:spring-webmvc")
    implementation("org.springframework:spring-jdbc")
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.3"))
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
    compileOnly("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    compileOnly("jakarta.validation:jakarta.validation-api:3.0.2")
    testImplementation("com.tngtech.archunit:archunit:1.4.1")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework:spring-test")
    testImplementation("jakarta.servlet:jakarta.servlet-api:6.0.0")
    testImplementation("jakarta.annotation:jakarta.annotation-api:2.1.1")
    testImplementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    testImplementation("jakarta.validation:jakarta.validation-api:3.0.2")
    testImplementation("org.hamcrest:hamcrest:3.0")
}

val generated = layout.buildDirectory.dir("generated/openapi")
val openApiGenerate by
    tasks.registering(JavaExec::class) {
        classpath = generator
        mainClass.set("org.openapitools.codegen.OpenAPIGenerator")
        val spec = file("../openapi.yaml")
        inputs.file(spec)
        inputs.dir("openapi-templates")
        outputs.dir(generated)
        doFirst { generated.get().asFile.deleteRecursively() }
        args(
            "generate",
            "-g",
            "spring",
            "-i",
            spec.absolutePath,
            "-o",
            generated.get().asFile.absolutePath,
            "--api-package",
            "lab2.organizations.generated.api",
            "--model-package",
            "lab2.organizations.generated.model",
            "--template-dir",
            file("openapi-templates").absolutePath,
            "--global-property",
            "apis,models,apiTests=false,modelTests=false,apiDocs=false,modelDocs=false",
            "--additional-properties",
            "interfaceOnly=true,skipDefaultInterface=true,useJakartaEe=true,useSpringBoot3=false,annotationLibrary=none,documentationProvider=none,useSwaggerUI=false,withXml=true,useBeanValidation=false,openApiNullable=false,dateLibrary=java8,hideGenerationTimestamp=true",
        )
        inputs.property("generationArguments", args)
    }

sourceSets.main { java.srcDir(generated.map { it.dir("src/main/java") }) }

tasks.compileJava {
    dependsOn(openApiGenerate)
    options.encoding = "UTF-8"
}

tasks.test {
    useJUnitPlatform()
    environment("TEST_DATABASE_URL", System.getenv("TEST_DATABASE_URL") ?: "")
}

tasks.war { archiveFileName.set("organizations.war") }

val integrationTestSource =
    sourceSets.create("integrationTest") {
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += output + compileClasspath
    }

configurations[integrationTestSource.implementationConfigurationName].extendsFrom(
    configurations.testImplementation.get()
)

configurations[integrationTestSource.runtimeOnlyConfigurationName].extendsFrom(
    configurations.testRuntimeOnly.get()
)

tasks.register<Test>("integrationTest") {
    description = "Runs PostgreSQL integration tests; requires TEST_DATABASE_URL."
    group = "verification"
    testClassesDirs = integrationTestSource.output.classesDirs
    classpath = integrationTestSource.runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.test)
}
