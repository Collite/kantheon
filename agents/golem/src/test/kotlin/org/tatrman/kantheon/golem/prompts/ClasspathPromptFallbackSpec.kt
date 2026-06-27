package org.tatrman.kantheon.golem.prompts

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.string.shouldContain

/**
 * The bundled offline prompts (the classpath copy of the mounted Shem bundle) are
 * actually on the classpath under `prompts/<locale>/<name>.yaml` and carry the
 * load-bearing `{{ }}` placeholders the composer substitutes.
 */
class ClasspathPromptFallbackSpec :
    StringSpec({

        "the bundled intent + free-sql + chip-topup prompts load from the classpath" {
            val loaded = ClasspathPromptFallback(locale = "cs").load()
            loaded shouldContainKey "intent"
            loaded shouldContainKey "free-sql"
            loaded shouldContainKey "chip-topup"
        }

        "the intent prompt preserves the {{ question }} placeholder verbatim" {
            ClasspathPromptFallback(locale = "cs").load().getValue("intent") shouldContain "{{ question }}"
        }
    })
