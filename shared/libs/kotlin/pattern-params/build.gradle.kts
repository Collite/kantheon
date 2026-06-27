plugins {
    `java-library`
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
}

tasks.test {
    useJUnitPlatform()
}

dependencies {
    testImplementation(libs.bundles.kotest)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            groupId = "cz.tatrman"
            artifactId = "pattern-params"
            version = project.version.toString()
        }
    }
}
