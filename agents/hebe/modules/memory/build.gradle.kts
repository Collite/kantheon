plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":agents:hebe:modules:api"))
    implementation(project(":agents:hebe:modules:observability"))
    implementation(libs.sqlite.jdbc)
    implementation(libs.flyway.core)
    // Postgres memory backend (Hebe arc P3 S3.1): Exposed + HikariCP + Postgres driver
    // + Flyway-pgsql, the kantheon Exposed-DSL/Flyway convention (architecture §5.1).
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.hikaricp)
    implementation(libs.postgresql)
    implementation(libs.flyway.pgsql)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.bundles.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
}
