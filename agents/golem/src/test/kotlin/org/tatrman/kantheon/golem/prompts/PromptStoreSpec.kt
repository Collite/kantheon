package org.tatrman.kantheon.golem.prompts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Write `<dir>/prompts/<locale>/<name>.yaml` and return the Shem-bundle root `<dir>`. */
private fun mountShem(
    locale: String,
    prompts: Map<String, String>,
): Path {
    val root = Files.createTempDirectory("golem-shem")
    val dir = root.resolve("prompts").resolve(locale)
    dir.createDirectories()
    prompts.forEach { (name, content) -> dir.resolve("$name.yaml").writeText(content) }
    return root
}

/**
 * [PromptStore]: the mounted Shem bundle as the live source, the bundled classpath
 * fallback when nothing is mounted, verbatim `{{ }}` preservation, and the
 * no-downgrade-of-a-loaded-set rule (prompts are remount-driven).
 */
class PromptStoreSpec :
    StringSpec({

        "refresh loads prompts from the mounted Shem, preserving {{ }} placeholders verbatim" {
            val planPrompt = "Plán pro otázku: {{ question }}\nKontext: {{context}}"
            val dir = mountShem("cs", mapOf("intent" to planPrompt))
            val store = PromptStore(shemDir = dir, locale = "cs", fallback = { emptyMap() })

            val snap = store.refresh()

            store.isLoaded shouldBe true
            snap.source shouldBe PromptSource.MOUNTED
            store.prompt("intent") shouldBe planPrompt
        }

        "all three canonical prompts mount under the locale subdir" {
            val dir =
                mountShem(
                    "cs",
                    mapOf(
                        "intent" to "i {{ question }}",
                        "free-sql" to "fs {{ schema }}",
                        "chip-topup" to "ct {{ user_text }}",
                    ),
                )
            val store = PromptStore(shemDir = dir, locale = "cs", fallback = { emptyMap() })

            store.refresh()
            store.prompt("intent") shouldBe "i {{ question }}"
            store.prompt("free-sql") shouldBe "fs {{ schema }}"
            store.prompt("chip-topup") shouldBe "ct {{ user_text }}"
        }

        "a remount swaps in the new prompt content" {
            val dir = mountShem("cs", mapOf("intent" to "verze 1 {{ question }}"))
            val store = PromptStore(shemDir = dir, locale = "cs", fallback = { emptyMap() })

            store.refresh()
            store.prompt("intent") shouldBe "verze 1 {{ question }}"

            // simulate a remount: overwrite the mounted file, then refresh
            dir
                .resolve("prompts")
                .resolve("cs")
                .resolve("intent.yaml")
                .writeText("verze 2 {{ question }}")
            store.refresh()
            store.prompt("intent") shouldBe "verze 2 {{ question }}"
        }

        "no mounted prompts at boot falls back to the bundled prompts" {
            val missing = Files.createTempDirectory("golem-shem-empty")
            val store =
                PromptStore(shemDir = missing.resolve("absent"), locale = "cs", fallback = {
                    mapOf("intent" to "bundled {{ question }}")
                })

            val snap = store.refresh()
            snap.source shouldBe PromptSource.BUNDLED
            store.prompt("intent") shouldBe "bundled {{ question }}"
        }

        "an empty mount falls back to bundled when nothing is loaded" {
            val emptyMount = mountShem("cs", emptyMap())
            val store = PromptStore(shemDir = emptyMount, locale = "cs", fallback = { mapOf("intent" to "bundled") })

            store.refresh().source shouldBe PromptSource.BUNDLED
        }

        "a later mount outage does not downgrade an already-loaded set" {
            val dir = mountShem("cs", mapOf("intent" to "mounted {{ question }}"))
            val store = PromptStore(shemDir = dir, locale = "cs", fallback = { mapOf("intent" to "bundled") })

            val good = store.refresh()
            good.source shouldBe PromptSource.MOUNTED

            // wipe the mounted prompts, then refresh — the held set must not downgrade
            Files
                .walk(dir.resolve("prompts").resolve("cs"))
                .filter { Files.isRegularFile(it) }
                .forEach { Files.delete(it) }
            val afterOutage = store.refresh()
            afterOutage shouldBe good
            afterOutage.source shouldBe PromptSource.MOUNTED
        }
    })
