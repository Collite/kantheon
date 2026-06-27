plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
    application
    alias(libs.plugins.shadow)
    // Hebe arc P3 S3.3 — the k8s image (server-mode entrypoint). The local shadowJar
    // (hebe.jar) is retained; CI auto-detects Jib modules.
    alias(libs.plugins.jib)
}

application {
    mainClass.set("org.tatrman.kantheon.hebe.cli.MainKt")
    applicationName = "hebe"
}

jib {
    from {
        image = "eclipse-temurin:21-jre"
    }
    to {
        image = "hebe:dev"
    }
    container {
        mainClass = "org.tatrman.kantheon.hebe.cli.MainKt"
        // Server mode: the agent loop + web console + channels + scheduler, no TTY.
        args = listOf("run")
        ports = listOf("8765")
    }
}

tasks.shadowJar {
    archiveBaseName.set("hebe")
    archiveClassifier.set("")
    // Always version-less → `hebe.jar`, the stable artifact name `hebe.sh`/`hebe`
    // and the deploy story expect (the standalone build produced `hebe.jar`
    // because its version was unspecified; the merged build carries the root
    // `0.0.0-SNAPSHOT`, so pin the name here).
    archiveVersion.set("")
    manifest {
        attributes["Main-Class"] = "org.tatrman.kantheon.hebe.cli.MainKt"
    }
    mergeServiceFiles()
    isZip64 = true
}

dependencies {
    implementation(libs.clikt)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:config"))
    implementation(project(":agents:hebe:modules:gateway"))
    implementation(project(":agents:hebe:modules:channels:channel-manager"))
    implementation(project(":agents:hebe:modules:channels:cli"))
    implementation(project(":agents:hebe:modules:channels:web"))
    implementation(project(":agents:hebe:modules:channels:telegram"))
    implementation(project(":agents:hebe:modules:observability"))
    implementation(project(":agents:hebe:modules:security"))
    implementation(project(":agents:hebe:modules:tools:dispatch"))
    implementation(project(":agents:hebe:modules:plugins"))
    implementation(project(":agents:hebe:modules:mcp-server"))
    implementation(project(":agents:hebe:modules:core"))
    implementation(project(":agents:hebe:modules:memory"))
    implementation(project(":agents:hebe:modules:providers:openai-compat"))
    implementation(project(":agents:hebe:modules:tools:builtin"))
    implementation(project(":agents:hebe:modules:memory"))
    implementation(project(":agents:hebe:modules:scheduler"))
    implementation(project(":agents:hebe:modules:tools:mcp-client"))
    implementation(project(":agents:hebe:modules:providers:openai-compat"))
    implementation(libs.telegrambots.longpolling)
    implementation(libs.telegrambots.client.jetty)
    implementation(libs.ktor.server.core)
}
