package org.tatrman.kantheon.themis.config

import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.io.File

/**
 * Every env override named in `ResolverConfig` must actually be reachable.
 *
 * `ResolverConfig`'s `stringOrEnv`/`intOrEnv`/`longOrEnv`/`doubleOrEnv` helpers consult
 * `System.getenv` **only in their catch branch** — that is, only when the key is absent from
 * `application.conf`. So a key that carries a literal with no `${?VAR}` substitution line
 * silently wins over its env var, and the override becomes dead config that looks live: it
 * appears in the deployment, in `kubectl get deploy -o json`, in the values.yaml, and does
 * nothing.
 *
 * That is not hypothetical. `CONFIDENCE_THRESHOLD=0.55` was set on the hartland Themis pod
 * while the HITL gate kept comparing against the conf's 0.75, so a 0.62-confidence turn asked
 * for clarification instead of resolving — and took the agent-routing chips down with it,
 * since `AwaitingClarification` is a different `oneof` branch than `Resolution`. Four other
 * keys were dead the same way.
 *
 * This is a source-level guard rather than a runtime one because the failure is invisible at
 * runtime by construction: both readings produce a valid config, and only one of them is the
 * one the operator asked for.
 */
class EnvOverrideCoverageSpec :
    StringSpec({

        "every *OrEnv key in application.conf carries a \${?VAR} substitution line" {
            val conf = File("src/main/resources/application.conf")
            val source = File("src/main/kotlin/org/tatrman/kantheon/themis/config/ResolverConfig.kt")
            // Fail loudly rather than vacuously passing if either file is moved or renamed.
            conf.isFile shouldBe true
            source.isFile shouldBe true

            val confText = conf.readText()
            val calls = Regex("""\w+OrEnv\(\s*"([^"]+)"\s*,\s*"([^"]+)"""").findAll(source.readText())

            val dead =
                calls
                    .map { it.groupValues[1].substringAfterLast('.') to it.groupValues[2] }
                    .filter { (key, _) ->
                        val quoted = Regex.escape(key)
                        val declared = Regex("""^\s*$quoted\s*=""", RegexOption.MULTILINE).containsMatchIn(confText)
                        val substituted =
                            Regex("""^\s*$quoted\s*=\s*\$\{\?""", RegexOption.MULTILINE).containsMatchIn(confText)
                        declared && !substituted
                    }.map { (key, env) -> "$env (application.conf declares `$key` with a literal only)" }
                    .toList()

            withClue("dead env overrides — add a `<key> = \${?VAR}` line for each: $dead") {
                dead shouldBe emptyList()
            }
        }
    })
