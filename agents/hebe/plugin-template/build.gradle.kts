plugins {
    kotlin("jvm") version "2.3.20"
    kotlin("plugin.serialization") version "2.3.20"
}

group = "com.example"
version = "0.1.0"

repositories {
    mavenCentral()
    mavenLocal()
}

val apiJarPath: String? by project
val pluginApiJarPath: String? by project

dependencies {
    if (project.hasProperty("apiJarPath")) {
        @Suppress("UnstableApiUsage")
        compileOnly(files(apiJarPath))
    } else {
        @Suppress("UnstableApiUsage")
        compileOnly("org.tatrman.kantheon.hebe:api:0.1.0")
    }
    if (project.hasProperty("pluginApiJarPath")) {
        @Suppress("UnstableApiUsage")
        compileOnly(files(pluginApiJarPath))
    } else {
        @Suppress("UnstableApiUsage")
        compileOnly("org.tatrman.kantheon.hebe:plugin-api:0.1.0")
    }
    @Suppress("UnstableApiUsage")
    compileOnly("org.pf4j:pf4j:3.15.0")
    @Suppress("UnstableApiUsage")
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks.jar {
    archiveBaseName.set("hello-plugin")
}

tasks.register<Jar>("javadocJar") {
    archiveClassifier.set("javadoc")
    enabled = false
}