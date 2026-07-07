plugins {
    kotlin("jvm") version "2.4.0"
    `java-library`
    `maven-publish`
}

group = "io.github.rehody.layercache"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)

    testImplementation(kotlin("test"))
    testImplementation(libs.bundles.test.suite)
    testRuntimeOnly(libs.junit.jupiter.engine)
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            from(components["java"])
        }
    }
}