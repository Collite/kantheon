plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
    `maven-publish`
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "org.tatrman.kantheon.hebe"
            artifactId = "plugin-api"
            version = "0.1.0"
            artifact(tasks.named("jar"))
        }
    }
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    api(libs.kotlinx.coroutines.core)
    api(libs.pf4j)
    api(libs.kotlinx.serialization.json)
}
