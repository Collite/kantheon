package org.tatrman.kantheon.pythia.plan

/**
 * Loads externalised prompts from the classpath (`prompts/<locale>/<name>.md`),
 * falling back to English. `{{ placeholder }}` tokens are substituted at use time
 * (golem `PromptStore` idiom). Prompts live with the agent (architecture §3).
 */
class Prompts(
    private val loader: (String) -> String? = { path ->
        Prompts::class.java.classLoader
            .getResourceAsStream(path)
            ?.bufferedReader()
            ?.readText()
    },
) {
    fun load(
        locale: String,
        name: String,
    ): String {
        val lang = locale.take(2).ifBlank { "en" }
        return loader("prompts/$lang/$name.md")
            ?: loader("prompts/en/$name.md")
            ?: error("prompt '$name' not found for locale '$locale' or en")
    }

    companion object {
        private val TOKEN = Regex("""\{\{\s*(\w+)\s*}}""")

        fun substitute(
            template: String,
            vars: Map<String, String>,
        ): String = TOKEN.replace(template) { vars[it.groupValues[1]] ?: "" }
    }
}
