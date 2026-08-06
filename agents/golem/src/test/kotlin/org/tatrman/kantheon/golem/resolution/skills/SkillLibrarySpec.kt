package org.tatrman.kantheon.golem.resolution.skills

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import org.tatrman.ttr.lexicon.EntryProvenance
import org.tatrman.ttr.lexicon.OperatorEntry
import org.tatrman.ttr.lexicon.OperatorLibrary
import org.tatrman.ttr.lexicon.sha256
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * RV-P5.3 T3 — the operator-library loader.
 *
 * The interesting property is not that JSON parses; it is that this loader and the compiler
 * cannot drift, because the loader uses the compiler's own types (`OperatorLibrary`,
 * `OperatorEntry`) and the compiler's own hash function ([sha256]). golem-py re-declares all
 * three and is one careless edit from disagreeing about what a checksum is.
 */
internal fun fixtureJson(): String =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream("skills/operator-library.json")) {
        "skills/operator-library.json is not on the test classpath"
    }.use { it.readBytes().toString(Charsets.UTF_8) }

internal fun body(
    op: String,
    text: String,
    version: Int = 1,
    file: String = "estate/skills/$op.md",
): Pair<String, OperatorEntry> =
    op to
        OperatorEntry(
            body = text,
            version = version,
            checksum = sha256(text.toByteArray(Charsets.UTF_8)),
            source = EntryProvenance(file = file, line = 1),
        )

internal fun layer(vararg entries: Pair<String, OperatorEntry>): SkillLayer =
    SkillLayer.fromJson(OperatorLibrary(operators = entries.toMap()).toJson())

private const val UPSTREAM_LIBRARY = "services/golem-py/tests/fixtures/lexicon/operator-library.json"

/**
 * The document with `source.layer` stripped wherever it appears — the one edit this copy
 * carries, and the only difference the drift check may tolerate.
 */
private fun withoutSourceLayer(text: String): JsonElement {
    fun strip(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject ->
                JsonObject(
                    element
                        .filterKeys { it != "layer" || "file" !in element }
                        .mapValues { (_, v) -> strip(v) },
                )
            is JsonArray -> JsonArray(element.map(::strip))
            else -> element
        }
    return strip(Json.parseToJsonElement(text))
}

class SkillLibrarySpec :
    StringSpec({

        "the vendored fixture is the one PROVENANCE.md records" {
            val actual =
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(fixtureJson().toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
            actual shouldBe "2b6b87a9fa12bbf510a1b81d9c1079c77b01dfe47d38fbb13dfb361edcbaf107"
        }

        "the vendored fixture matches its original MODULO the one documented edit" {
            // RV-P5.4 T5 — the nightly's half of the drift check, and it cannot be a byte
            // comparison: this copy is deliberately NOT byte-identical, because the source
            // carries an invented `source.layer` key the published `EntryProvenance` rejects.
            //
            // So the comparison is "identical after removing exactly the key we removed". That
            // tolerates the documented edit and nothing else — and it tells us the day upstream
            // drops the key too, which is P5.4's carry (3) closing.
            val siblingRoot = System.getenv("TATRMAN_SERVER_DIR")
            if (siblingRoot == null) {
                println(
                    "SKIPPED cross-repo drift check: set TATRMAN_SERVER_DIR to a tatrman-server " +
                        "checkout to diff skills/operator-library.json against " +
                        "$UPSTREAM_LIBRARY. The sha256 test above still ran — it proves this file " +
                        "has not changed HERE, not that it still matches THERE.",
                )
            } else {
                val upstream = Path.of(siblingRoot, UPSTREAM_LIBRARY)
                // ⛑ A MISSING original is a drift, not a skip — same correction as
                // `RecordedCoreProvenanceSpec`. Absence used to print and pass, so an upstream
                // rename produced a green nightly.
                withClue("no such file at $upstream — the original moved or was deleted upstream") {
                    Files.exists(upstream) shouldBe true
                }
                val theirs = withoutSourceLayer(Files.readString(upstream))
                withClue("$upstream drifted beyond the documented `source.layer` removal") {
                    theirs shouldBe withoutSourceLayer(fixtureJson())
                }
                if (theirs == Json.parseToJsonElement(Files.readString(upstream))) {
                    println(
                        "NOTE: upstream no longer carries `source.layer` — RV-P5.4 carry (3) is " +
                            "closed; re-copy byte-for-byte and delete this normalisation.",
                    )
                }
            }
        }

        "⚑ the fixture parses against the PUBLISHED artifact type, which the source copy did not" {
            // The finding this test is the record of: golem-py's fixture carries a
            // `source.layer: "STDLIB"` key. `EntryProvenance` is `{file, line}` and the
            // compiler has no `layer` to emit, so the published `OperatorLibrary` — strict
            // JSON, `ignoreUnknownKeys = false` — REFUSES it. golem-py's hand-rolled loader
            // accepts it (`(entry.get("source") or {}).get("file", "")` never looks), so the
            // two shells disagree about what a valid artifact is, and only this one is
            // checking against the producer's real schema. The vendored copy has the invented
            // key removed; the fix belongs upstream too (carried to P5.4).
            fixtureJson() shouldNotContain "\"layer\""
            SkillLayer
                .fromJson(fixtureJson())
                .bodies.values
                .forEach { it.sourceFile shouldNotBe "" }
        }

        "the five stdlib bodies load, with their sections split" {
            val loaded = SkillLayer.fromJson(fixtureJson(), archiveId = "fixture")

            loaded.bodies.keys.sorted() shouldContainExactly
                listOf("op:compare", "op:share-of", "op:show", "op:top-n", "op:trend")
            loaded.schemaVersion shouldBe OperatorLibrary.SCHEMA_VERSION

            val trend = loaded.get("op:trend")
            trend.retrieval shouldContain "finest time grain"
            trend.formatting shouldContain "line chart"
            // Parsed out of PROSE, not out of a field — see FINDING_REQUIRES.
            trend.requires shouldContainExactly listOf("time-grain")
            trend.sourceFile shouldBe "lexicon-stdlib/skills/trend.md"
        }

        "a body with no Applicability section declares no requirements" {
            SkillLayer.fromJson(fixtureJson()).get("op:show").requires shouldBe emptyList()
        }

        // ------------------------------------------------------------------- the checksum

        "a tampered body refuses to load rather than shaping a retrieval" {
            val honest = body("op:trend", "Retrieval: group by month.")
            val tampered = honest.first to honest.second.copy(body = "Retrieval: group by DECADE.")

            val e = shouldThrow<SkillException> { layer(tampered) }
            e.message shouldContain "checksum mismatch"
        }

        "an empty checksum is not a failure — it is an artifact that declared none" {
            val entry =
                "op:x" to
                    OperatorEntry(
                        body = "Retrieval: everything.",
                        version = 1,
                        checksum = "",
                        source = EntryProvenance("x.md", 1),
                    )
            layer(entry).get("op:x").retrieval shouldBe "everything."
        }

        "the checksum rule is the compiler's own function, not a re-implementation" {
            // If `ttr-lexicon` ever changes how it hashes, this fails here rather than in
            // production — which is the entire reason the dependency is worth taking.
            val text = "Retrieval: something."
            sha256(text.toByteArray(Charsets.UTF_8)) shouldBe
                "sha256:" +
                MessageDigest
                    .getInstance("SHA-256")
                    .digest(text.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
        }

        "an unknown schema id refuses" {
            val bad = OperatorLibrary(schemaVersion = "ttr-operator-library/v2").toJson()
            shouldThrow<SkillException> { SkillLayer.fromJson(bad) }.message shouldContain
                "unknown operator-library schema"
        }

        "unreadable JSON names the archive it came from" {
            shouldThrow<SkillException> { SkillLayer.fromJson("{not json", archiveId = "/mnt/lex.tar.zst") }
                .message shouldContain "/mnt/lex.tar.zst"
        }

        // -------------------------------------------------------------- estate over stdlib

        "estate wins over stdlib — first layer that has the op" {
            val estate = layer(body("op:trend", "Retrieval: ESTATE rule.", file = "estate/trend.md"))
            val stdlib = layer(body("op:trend", "Retrieval: stdlib rule."), body("op:show", "Retrieval: plainly."))
            val library = LayeredSkillLibrary(listOf(estate, stdlib))

            library.get("op:trend").retrieval shouldBe "ESTATE rule."
            // …and stdlib still supplies what the estate did not override.
            library.get("op:show").retrieval shouldBe "plainly."
            library.known() shouldContainExactly listOf("op:show", "op:trend")
        }

        "an op in no layer refuses by name — the question named an action this estate lacks" {
            val library = LayeredSkillLibrary(listOf(layer(body("op:show", "Retrieval: plainly."))))
            ("op:trend" in library) shouldBe false
            shouldThrow<SkillException> { library.get("op:trend") }.message shouldContain "op:trend"
        }

        "an empty library is a legal state, not an error" {
            // An estate that declared no operators. The fast path reads this as "this Golem
            // was taught no actions", which is different from "the mount is broken".
            LayeredSkillLibrary.EMPTY.known() shouldBe emptyList()
            ("op:show" in LayeredSkillLibrary.EMPTY) shouldBe false
        }

        "an unset mount env var yields an empty library and says so" {
            LayeredSkillLibrary.fromEnv(env = emptyMap()).known() shouldBe emptyList()
        }
    })
