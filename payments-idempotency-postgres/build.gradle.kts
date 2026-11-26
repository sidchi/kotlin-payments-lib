import io.gitlab.arturbosch.detekt.Detekt
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.detekt)
    `java-library`
}

description = "Postgres-backed IdempotencyStore for kotlin-payments-lib."

java {
    // Library consumers may still be on Java 17; we build on 21 but emit 17 bytecode.
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    withSourcesJar()
}

kotlin {
    // Public library: every public declaration must state its visibility and type.
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        allWarningsAsErrors.set(true)
    }
}

dependencies {
    api(project(":payments-core"))
    // Plain JDBC (javax.sql.DataSource) at compile time; the application brings the driver and pool.
    testImplementation(testFixtures(project(":payments-core")))
    testImplementation(libs.postgresql)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotest.runner.junit5)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.property)
    testRuntimeOnly(libs.junit.platform.launcher)

    detektPlugins(libs.detekt.formatting)
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    parallel = true
}

// detekt runs on its own embedded Kotlin compiler; keep it on the version it was built with
// instead of letting the Kotlin Gradle plugin align it to the project's compiler.
configurations.matching { it.name == "detekt" || it.name == "detektPlugins" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(libs.versions.detekt.kotlin.get())
        }
    }
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "17"
    reports {
        html.required.set(true)
        sarif.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        md.required.set(false)
    }
}

tasks.test {
    useJUnitPlatform()
    // Integration tests read PAYMENTS_TEST_POSTGRES_URL from the environment; declare it as an input so a cached
    // "skipped" run is never reused once a database is configured.
    inputs.property("postgresUrlConfigured", System.getenv("PAYMENTS_TEST_POSTGRES_URL") != null)
    testLogging {
        events("failed", "skipped")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
