plugins {
    kotlin("jvm") version "1.9.0"
    application
}

group = "me.user"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.16.0")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.16.0")
    implementation("io.ktor:ktor-client-core:2.3.2")
    implementation("io.ktor:ktor-client-apache:2.3.2")
    implementation("io.ktor:ktor-client-jackson:2.3.2") // TODO is this needed?
    implementation("io.ktor:ktor-serialization-jackson:2.3.2")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.2")
    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(8)
}

application {
    mainClass.set("MainKt")
}
