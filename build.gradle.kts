import io.gitlab.arturbosch.detekt.extensions.DetektExtension

plugins {
    base
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ktlint) apply false
    // Hebe (P1 Stage 1.1) — on the root buildscript classpath (apply false) so
    // the typed DetektExtension is configurable in the Hebe-scoped block below.
    // detekt is applied only to the `:agents:hebe:` modules (their own plugins{}).
    alias(libs.plugins.detekt) apply false
}

allprojects {
    group = "org.tatrman.kantheon"
    version = providers.gradleProperty("kantheon.version").orNull ?: "0.0.0-SNAPSHOT"
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
        }

        // ── Component test tier (testing arc — Phase 1 Stage 1.1) ────────────
        // A dedicated `componentTest` source set (`src/componentTest/kotlin`)
        // for **real-dependency** Testcontainers specs, registered via the JVM
        // Test Suite plugin for every Kotlin module. It is physically separate
        // from `src/test`, so the `test` PR gate stays mocked-only
        // (planning-conventions §4 / testing architecture §2).
        //
        // Kotest ships its own JUnit-Platform engine, so we deliberately do NOT
        // call `useJUnitJupiter()` and do NOT filter by platform `includeTags`:
        // Kotest tags are not JUnit-Platform tags, so `includeTags("component")`
        // would silently match nothing and skip every spec. Specs carry Kotest's
        // `@Tags("component")` as the marker; the real isolation guarantee is the
        // source-set split plus the `test`-classpath guard below.
        extensions.configure<org.gradle.testing.base.TestingExtension> {
            suites.register("componentTest", org.gradle.api.plugins.jvm.JvmTestSuite::class.java) {
                dependencies {
                    // The suite `dependencies {}` DSL (DependencyCollector) takes
                    // single-dependency providers, not bundle providers — so the
                    // `kotest` bundle is expanded into its members here.
                    implementation(project())
                    implementation(libs.kotest.runner.junit5)
                    implementation(libs.kotest.assertions.core)
                    implementation(libs.kotest.property)
                    implementation(libs.testcontainers)
                }
                targets.configureEach {
                    testTask.configure {
                        useJUnitPlatform()
                        // Never piggy-back on the unit run; component is its own tier.
                        shouldRunAfter(tasks.named("test"))
                        // For the cross-repo drift guard (ContextNameRegistrySpec):
                        // the repo root to scan + the optional olymp checkout dir.
                        systemProperty("integrationHarness.repoRoot", rootProject.projectDir.absolutePath)
                        providers.gradleProperty("olympDir").orNull?.let { systemProperty("olympDir", it) }
                    }
                }
            }

            // ── Integration test tier (testing arc — Phase 2 Stage 2.1) ──────
            // A `integrationTest` source set (`src/integrationTest/kotlin`) for
            // the **full forked constellation on a cluster**. It is cluster-bound
            // and **skips** unless a context is named (`-Pcontext`), so
            // `./gradlew check` and everyday local builds never need a cluster.
            // Bring-up/teardown is olymp's (architecture §3); this side is
            // read-only + fail-fast (the @RequiresContext gate). Specs carry
            // Kotest's `@Tags("integration")`.
            suites.register("integrationTest", org.gradle.api.plugins.jvm.JvmTestSuite::class.java) {
                dependencies {
                    implementation(project())
                    implementation(libs.kotest.runner.junit5)
                    implementation(libs.kotest.assertions.core)
                    implementation(libs.kotest.property)
                }
                targets.configureEach {
                    testTask.configure {
                        useJUnitPlatform()
                        shouldRunAfter(tasks.named("test"))
                        // Cluster-bound: only run when a context is named.
                        onlyIf { providers.gradleProperty("context").isPresent }
                        // Forward the cross-repo context-name contract (contracts
                        // §2) to the forked test JVM.
                        providers.gradleProperty("context").orNull?.let { systemProperty("context", it) }
                        providers.gradleProperty("namespace").orNull?.let { systemProperty("namespace", it) }
                        providers.gradleProperty("olympDir").orNull?.let { systemProperty("olympDir", it) }
                    }
                }
            }
        }

        // T5/§4 isolation guard. `just test-all` (`./gradlew test`) must never run
        // a component- or integration-tier spec. Fail fast if either tier's output
        // leaks onto the unit `test` runtime classpath. Class dirs are captured at
        // configuration time (the Test task's own `extensions` is not the
        // project's, and the project must not be touched during execution).
        val higherTierClassesDirs =
            extensions.getByType<org.gradle.api.tasks.SourceSetContainer>().let { sets ->
                sets["componentTest"].output.classesDirs + sets["integrationTest"].output.classesDirs
            }
        tasks.named<org.gradle.api.tasks.testing.Test>("test") {
            doFirst {
                val higherTierFiles = higherTierClassesDirs.files
                val leaked = classpath.files.filter { it in higherTierFiles }
                require(leaked.isEmpty()) {
                    "Higher-tier (component/integration) classes on the unit `test` classpath: $leaked. " +
                        "test-all must stay mocked-only (planning-conventions §4)."
                }
            }
        }
    }

    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        // ktlint config picked up from .editorconfig at repo root.
    }
}

// ── Hebe subtree (P1 Stage 1.1 — gradle merge) ───────────────────────────────
// The Hebe modules carry conventions the rest of kantheon doesn't, which the
// retired `hebe.base`/`hebe.library` convention plugins used to inject:
//   • detekt with the custom **mutation-funnel rule** — every state change must
//     flow through `ToolDispatcher.dispatch` (architecture §4.2/§4.3); this is
//     Hebe's most valuable invariant and survives the merge intact.
//   • the `-Xjsr305=strict` compiler arg.
//   • JUnit-Platform on the unit `test` task (Hebe mixes Kotest + JUnit Jupiter).
//   • a common test-dependency set (junit-jupiter + kotest-assertions + mockk).
// Scoped to `:agents:hebe:` so kantheon-native modules (ktlint-only) are
// untouched. detekt config lives at agents/hebe/config/detekt/ (unchanged).
subprojects {
    if (!path.startsWith(":agents:hebe:")) return@subprojects

    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            compilerOptions {
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }

        tasks.named<org.gradle.api.tasks.testing.Test>("test") {
            useJUnitPlatform()
        }

        // The `hebe.base` common test stack (the retired convention applied it to
        // every module; several modules rely on it transitively).
        dependencies {
            add("testImplementation", libs.junit.jupiter)
            add("testImplementation", libs.kotest.assertions)
            add("testImplementation", libs.mockk)
            add("testRuntimeOnly", libs.junit.platform.launcher)
        }
    }

    plugins.withId("io.gitlab.arturbosch.detekt") {
        extensions.configure<DetektExtension> {
            config.setFrom(rootProject.file("agents/hebe/config/detekt/detekt.yml"))
            // Per-module baseline (the detekt-idiomatic multi-module pattern):
            // the malformed single `config/detekt/baseline.xml` from the
            // standalone repo was a report dump, not a `SmellBaseline`, so detekt
            // ignored it — the pre-existing generic-rule debt (LongMethod,
            // MagicNumber, …) is captured in proper per-module baselines instead.
            // The custom **mutation-funnel** rule stays a hard gate: the current
            // code has zero violations, so none are baselined.
            baseline = file("detekt-baseline.xml")
            buildUponDefaultConfig = true
            autoCorrect = false
        }
        // Load the custom rule set — but the rule module must not depend on itself.
        if (path != ":agents:hebe:modules:detekt-rules") {
            dependencies {
                add("detektPlugins", project(":agents:hebe:modules:detekt-rules"))
            }
        }
    }

    // Per-module ktlint baseline for the Hebe subtree. The Hebe sources carry
    // pre-existing ktlint debt under their own `.editorconfig` (`root = true`,
    // max_line_length = 140) plus version-drift findings — kantheon pins ktlint
    // 14.0.1 vs Hebe's standalone 14.2.0, whose `property-naming` rule allows
    // const-valued SCREAMING_CASE `val`s that 14.0.1 flags. The baseline captures
    // that debt so `just lint-all` is green while ktlint stays a live gate on new
    // code; Stage 1.2's rename will regenerate it. (No source edits in P1 —
    // `local` must stay byte-for-byte.)
    plugins.withId("org.jlleitschuh.gradle.ktlint") {
        extensions.configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
            baseline.set(file("ktlint-baseline.xml"))
        }
    }
}
