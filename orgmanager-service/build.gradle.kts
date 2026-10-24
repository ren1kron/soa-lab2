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
    implementation(platform("com.fasterxml.jackson:jackson-bom:2.18.3"))
    implementation("com.fasterxml.jackson.dataformat:jackson-dataformat-xml")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.7")
    implementation("org.flywaydb:flyway-core:11.8.2")
    implementation("org.flywaydb:flyway-database-postgresql:11.8.2")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    compileOnly("jakarta.servlet:jakarta.servlet-api:6.0.0")
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.0.1")
    compileOnly("jakarta.inject:jakarta.inject-api:2.0.1")
    compileOnly("jakarta.annotation:jakarta.annotation-api:2.1.1")
    compileOnly("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    compileOnly("jakarta.validation:jakarta.validation-api:3.0.2")
    testImplementation("com.tngtech.archunit:archunit:1.4.1")
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("jakarta.ws.rs:jakarta.ws.rs-api:3.1.0")
    testImplementation("jakarta.annotation:jakarta.annotation-api:2.1.1")
    testImplementation("jakarta.xml.bind:jakarta.xml.bind-api:4.0.2")
    testImplementation("jakarta.validation:jakarta.validation-api:3.0.2")
}

fun registerGeneration(taskName: String, spec: String, pkg: String, modelsOnly: Boolean = false) =
    tasks.register<JavaExec>(taskName) {
        val output = layout.buildDirectory.dir("generated/$taskName")
        classpath = generator
        mainClass.set("org.openapitools.codegen.OpenAPIGenerator")
        inputs.file(file(spec))
        outputs.dir(output)
        doFirst { output.get().asFile.deleteRecursively() }
        args(
            "generate",
            "-g",
            "jaxrs-spec",
            "-i",
            file(spec).absolutePath,
            "-o",
            output.get().asFile.absolutePath,
            "--api-package",
            "$pkg.api",
            "--model-package",
            "$pkg.model",
            "--global-property",
            (if (modelsOnly) "models" else "apis,models") +
                ",apiTests=false,modelTests=false,apiDocs=false,modelDocs=false",
            "--additional-properties",
            "interfaceOnly=true,useJakartaEe=true,returnResponse=true,generatePom=false,generateRootResources=false,useSwaggerAnnotations=false,withXml=true,useBeanValidation=false,openApiNullable=false,dateLibrary=java8,hideGenerationTimestamp=true,sourceFolder=src/main/java",
        )
        inputs.property("generationArguments", args)
        sourceSets.main { java.srcDir(output.map { it.dir("src/main/java") }) }
    }

val openApiGenerate =
    registerGeneration("openApiGenerate", "../openapi-manager.yaml", "lab2.manager.generated")
val generateUpstreamModels =
    registerGeneration(
        "generateUpstreamModels",
        "../openapi.yaml",
        "lab2.manager.generated.upstream",
        true,
    )

tasks.compileJava {
    dependsOn(openApiGenerate, generateUpstreamModels)
    options.encoding = "UTF-8"
}

tasks.test { useJUnitPlatform() }

tasks.war { archiveFileName.set("orgmanager.war") }

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
