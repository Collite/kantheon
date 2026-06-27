package org.tatrman.kantheon.midas.core.infra

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import org.tatrman.kantheon.midas.v1.AssetKind
import org.tatrman.kantheon.midas.v1.AssetStatus
import org.tatrman.kantheon.midas.v1.ClientStatus
import org.tatrman.kantheon.midas.v1.CostBasisMethod
import org.tatrman.kantheon.midas.v1.LoaderRunStatus
import org.tatrman.kantheon.midas.v1.PortfolioStatus
import org.tatrman.kantheon.midas.v1.PortfolioType
import org.tatrman.kantheon.midas.v1.ReconcileStatus
import org.tatrman.kantheon.midas.v1.TransactionKind
import org.tatrman.kantheon.midas.v1.TransactionSource

/**
 * The real proto↔DDL drift guard (complements the proto-only `MidasEnumDdlMappingSpec`
 * in shared/proto): it parses the **actual** `CHECK (col IN ('A','B'))` literals out
 * of the shipped `V0001__schema.sql` migration and asserts every midas/v1 enum's
 * prefix-stripped constant set matches one of them verbatim. So if anyone edits a
 * CHECK constraint (or an enum) without the other, this fails — the failure mode the
 * hardcoded-set spec could not catch (the repository maps via prefix-strip, so the
 * two must stay in lockstep).
 */
class EnumDdlConstraintSpec :
    StringSpec({

        // Every `IN ( '...' , '...' )` literal set found in V0001 (CHECK constraints).
        val ddlInSets: Set<Set<String>> =
            run {
                val sql =
                    EnumDdlConstraintSpec::class.java
                        .getResourceAsStream("/db/migration/V0001__schema.sql")
                        ?.bufferedReader()
                        ?.use { it.readText() }
                        ?: error("V0001__schema.sql not found on the test classpath")
                Regex("""IN\s*\(([^)]*)\)""")
                    .findAll(sql)
                    .map { match ->
                        match
                            .groupValues[1]
                            .split(",")
                            .map { it.trim().trim('\'') }
                            .filter { it.isNotEmpty() }
                            .toSet()
                    }.filter { it.isNotEmpty() }
                    .toSet()
            }

        fun ddlForms(
            names: List<String>,
            prefix: String,
        ): Set<String> = names.filterNot { it == "UNRECOGNIZED" }.map { it.removePrefix(prefix) }.toSet()

        val cases =
            listOf(
                Triple("ClientStatus", ClientStatus.values().map { it.name }, "CLIENT_"),
                Triple("PortfolioType", PortfolioType.values().map { it.name }, "PORTFOLIO_"),
                Triple("PortfolioStatus", PortfolioStatus.values().map { it.name }, "PORTFOLIO_"),
                Triple("CostBasisMethod", CostBasisMethod.values().map { it.name }, "COST_BASIS_"),
                Triple("AssetKind", AssetKind.values().map { it.name }, "ASSET_"),
                Triple("AssetStatus", AssetStatus.values().map { it.name }, "ASSET_"),
                Triple("TransactionKind", TransactionKind.values().map { it.name }, "TX_"),
                Triple("TransactionSource", TransactionSource.values().map { it.name }, "TX_SRC_"),
                Triple("ReconcileStatus", ReconcileStatus.values().map { it.name }, "RECON_"),
                Triple("LoaderRunStatus", LoaderRunStatus.values().map { it.name }, "LR_"),
            )

        cases.forEach { (label, names, prefix) ->
            "$label constants match a V0001 CHECK IN(...) set verbatim" {
                ddlInSets shouldContain ddlForms(names, prefix)
            }
        }
    })
