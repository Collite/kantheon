package org.tatrman.kantheon.golem.format

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import org.tatrman.meta.v1.DrillMapDetail
import org.tatrman.meta.v1.LocalizedString
import org.tatrman.meta.v1.ModelBundle
import org.tatrman.meta.v1.ModelBundleQuery
import org.tatrman.meta.v1.ObjectDescriptor
import org.tatrman.meta.v1.QueryDescriptor
import org.tatrman.meta.v1.QueryParameterDef
import org.tatrman.meta.v1.SearchHints
import org.tatrman.kantheon.golem.context.ModelSnapshot
import org.tatrman.plan.v1.QualifiedName
import org.tatrman.kantheon.themis.v1.Themis.DomainEntityBinding
import org.tatrman.kantheon.themis.v1.Themis.EntityBinding

private fun qname(name: String) = QualifiedName.newBuilder().setName(name).build()

private fun pattern(
    id: String,
    params: List<Pair<String, Boolean>> = emptyList(), // name to optional
    examples: List<String> = emptyList(),
): ModelBundleQuery =
    ModelBundleQuery
        .newBuilder()
        .setObjectDescriptor(ObjectDescriptor.newBuilder().setLocalName(id).setQualifiedName(qname(id)))
        .setQueryDescriptor(
            QueryDescriptor.newBuilder().setSearch(SearchHints.newBuilder().addAllExamples(examples)),
        ).addAllParameters(
            params.map { (n, opt) ->
                QueryParameterDef
                    .newBuilder()
                    .setName(n)
                    .setType("varchar")
                    .setOptional(opt)
                    .build()
            },
        ).build()

class ChipsAndDrilldownsSpec :
    StringSpec({

        // ---- HeuristicChips (ucetnictvi literals) -------------------------------
        "heuristic: KOD_STR → 'Detail střediska', KOD_UCTU → 'Detail účtu'" {
            val rows =
                buildJsonArray {
                    addJsonObject {
                        put("KOD_STR", "DF01")
                        put("KOD_UCTU", "518")
                    }
                }
            val chips = HeuristicChips.derive(rows, rowCount = 1)
            chips.map { it.display } shouldContain "Detail střediska"
            chips.map { it.display } shouldContain "Detail účtu"
            chips.all { it.source == "heuristic" } shouldBe true
        }

        "heuristic: UCET_OBD with >1 distinct values offers a period comparison" {
            val rows =
                buildJsonArray {
                    addJsonObject { put("UCET_OBD", "2026.01") }
                    addJsonObject { put("UCET_OBD", "2026.02") }
                }
            HeuristicChips.derive(rows, rowCount = 2).map { it.display } shouldContain "Porovnej s předchozím obdobím"
        }

        "heuristic: hitting the 100-row cap offers to narrow / raise the limit" {
            val rows = buildJsonArray { addJsonObject { put("X", 1) } }
            HeuristicChips.derive(rows, rowCount = 100).map { it.display } shouldContain "Filtrovat / zvýšit limit"
        }

        // ---- PatternDerivedChips ------------------------------------------------
        "pattern-derived: a sibling pattern whose param the bindings fill is suggested, prefilled" {
            val model =
                ModelSnapshot.from(
                    ModelBundle
                        .newBuilder()
                        .addPatternQueries(pattern("picked", listOf("kod_str" to false)))
                        .addPatternQueries(
                            pattern(
                                "detailUctu",
                                listOf("kod_str" to false),
                                examples = listOf("Detail účtu {kod_str}"),
                            ),
                        ).build(),
                )
            val bindings =
                listOf(
                    EntityBinding
                        .newBuilder()
                        .setDomain(
                            DomainEntityBinding.newBuilder().setEntityTypeRef("kod_str").setResolvedLabel("DF01"),
                        ).build(),
                )
            val chips = PatternDerivedChips.derive("picked", bindings, model)
            chips shouldHaveSize 1
            chips.first().patternId shouldBe "detailUctu"
            chips.first().display shouldBe "Detail účtu DF01"
            chips.first().source shouldBe "pattern_derived"
        }

        // ---- Drilldowns ---------------------------------------------------------
        "drilldowns: explicit_ttr from the drill map + override_auto suppresses the auto twin" {
            val drill =
                DrillMapDetail
                    .newBuilder()
                    .setName("toDetail")
                    .setFromPattern(qname("agg"))
                    .setToPattern(qname("detail"))
                    .putArgMapping("kod", "KOD")
                    .setExplicit(true)
                    .setOverrideAuto(true)
                    .setDisplay(LocalizedString.newBuilder().putByLanguage("cs", "Detail dokladu"))
                    .build()
            val model =
                ModelSnapshot.from(
                    ModelBundle
                        .newBuilder()
                        .addPatternQueries(pattern("agg"))
                        .addPatternQueries(pattern("detail", listOf("KOD" to false)))
                        .addDrillMaps(drill)
                        .build(),
                )
            val rows = buildJsonArray { addJsonObject { put("KOD", "X1") } }
            val dds = Drilldowns.derive("agg", rows, model)
            // Exactly the explicit drill — its override_auto suppresses the auto_overlap twin to `detail`.
            dds shouldHaveSize 1
            dds.first().source shouldBe "explicit_ttr"
            dds.first().display shouldBe "Detail dokladu"
            dds.first().targetPatternId shouldBe "detail"
        }

        "drilldowns: auto_overlap when every required param matches a result column" {
            val model =
                ModelSnapshot.from(
                    ModelBundle
                        .newBuilder()
                        .addPatternQueries(pattern("agg"))
                        .addPatternQueries(pattern("detail", listOf("KOD" to false)))
                        .build(),
                )
            val rows = buildJsonArray { addJsonObject { put("KOD", "X1") } }
            val dds = Drilldowns.derive("agg", rows, model)
            dds shouldHaveSize 1
            dds.first().source shouldBe "auto_overlap"
            dds.first().targetPatternId shouldBe "detail"
            dds.first().argMappingMap["KOD"] shouldBe "KOD"
        }
    })
