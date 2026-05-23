plugins {
    java
    id("org.springframework.boot") version "4.0.0"
    id("io.spring.dependency-management") version "1.1.6"
    id("com.diffplug.spotless") version "6.25.0"
    checkstyle
}

group = "io.github.ramyagangapatnam"
version = "0.1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

extra["hapiHl7Version"] = "2.5.1"
extra["hapiFhirVersion"] = "7.4.0"
extra["openTelemetryVersion"] = "1.42.1"
extra["awsSdkVersion"] = "2.28.16"
extra["testcontainersVersion"] = "1.20.2"
extra["restAssuredVersion"] = "5.5.0"

dependencies {
    // Spring Boot starters
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")

    // Persistence
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    // HL7 v2
    implementation("ca.uhn.hapi:hapi-base:${property("hapiHl7Version")}")
    implementation("ca.uhn.hapi:hapi-structures-v25:${property("hapiHl7Version")}")

    // FHIR R4
    implementation("ca.uhn.hapi.fhir:hapi-fhir-structures-r4:${property("hapiFhirVersion")}")

    // JSON (Jackson is pulled in transitively by Spring; pinning here for clarity)
    implementation("com.fasterxml.jackson.core:jackson-databind")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    // OpenTelemetry (SDK + autoconfigure; agent is attached at JVM start via -javaagent)
    implementation("io.opentelemetry:opentelemetry-api:${property("openTelemetryVersion")}")
    implementation("io.opentelemetry:opentelemetry-sdk:${property("openTelemetryVersion")}")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp:${property("openTelemetryVersion")}")

    // AWS SDK v2 (S3 audit sink)
    implementation(platform("software.amazon.awssdk:bom:${property("awsSdkVersion")}"))
    implementation("software.amazon.awssdk:s3")

    // Tests
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.mockito:mockito-core")
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("io.rest-assured:rest-assured:${property("restAssuredVersion")}")

    testImplementation(platform("org.testcontainers:testcontainers-bom:${property("testcontainersVersion")}"))
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testImplementation("org.testcontainers:localstack")
}

tasks.withType<Test> {
    useJUnitPlatform()
    // Benchmarks are opt-in (Principle V; CI keeps PRs fast)
    val benchmark = System.getProperty("includeBenchmarks", "false").toBoolean()
    if (!benchmark) {
        useJUnitPlatform { excludeTags("benchmark") }
    }
}

checkstyle {
    toolVersion = "10.18.1"
    isIgnoreFailures = false
}

spotless {
    java {
        target("src/**/*.java")
        googleJavaFormat("1.23.0")
        removeUnusedImports()
        trimTrailingWhitespace()
        endWithNewline()
    }
    kotlinGradle {
        target("*.gradle.kts")
        ktlint("1.3.1")
    }
}

tasks.named("check") {
    dependsOn("spotlessCheck")
}
