package org.tatrman.kantheon.pythia.plan

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import org.tatrman.kantheon.common.v1.EntityBinding
import org.tatrman.kantheon.pythia.resolve.HandoffAnchor
import org.tatrman.kantheon.pythia.v1.Constraints
import org.tatrman.kantheon.pythia.v1.DepthBudget
import org.tatrman.kantheon.pythia.v1.IntentKind
import org.tatrman.kantheon.pythia.v1.ResolutionResult
import org.tatrman.kantheon.pythia.v1.ResolvedIntent

// A cross-domain plan that draws a query from each area (ERP unpaid invoices + HR wage).
private const val CROSS_DOMAIN_PLAN =
    """
    { "rationale": "join ERP receivables with HR wage cost across areas",
      "hypotheses": [ { "id": "H0", "statement": "data exists", "displayPriority": "HIDDEN" } ],
      "nodes": [
        { "nodeId": "N1", "testsHypIds": ["H0"], "kind": "query", "queryRef": "listUnpaidInvoices", "paramsJson": "{}" },
        { "nodeId": "N2", "testsHypIds": ["H0"], "kind": "query", "queryRef": "wageByCostCenter", "paramsJson": "{}" } ] }
    """

/**
 * Stage 5.1 T2 — a cross-domain investigation: the resolution spans two areas
 * (ERP customer + HR employee), the planner reads both Shems and the injected
 * context carries both areas' terminology + preferred queries into the planner prompt;
 * the drafted plan draws a capability from each area.
 */
class CrossDomainFixtureSpec :
    StringSpec({

        "a two-area resolution injects both Shems' context and drafts a cross-domain plan" {
            runTest {
                val llm = ScriptedPromptExecutor(listOf(CROSS_DOMAIN_PLAN))
                val planner =
                    Planner(
                        PlanComposer(llm),
                        PlanValidator(CapabilityChecker { it in setOf("listUnpaidInvoices", "wageByCostCenter") }),
                        shemReader = ShemReader { TWO_AREAS },
                    )
                val resolution =
                    ResolutionResult
                        .newBuilder()
                        .setResolvedIntent(
                            ResolvedIntent
                                .newBuilder()
                                .setKind(IntentKind.INTENT_RCA)
                                .addEntities(EntityBinding.newBuilder().setEntityType("customer"))
                                .addEntities(EntityBinding.newBuilder().setEntityType("employee")),
                        ).build()

                val result =
                    planner.plan(
                        resolution,
                        HandoffAnchor("", "", "", "", emptyList()),
                        Constraints.newBuilder().setDepthBudget(DepthBudget.DEPTH_NORMAL).build(),
                        "en",
                        listOf("listUnpaidInvoices", "wageByCostCenter"),
                    )

                result.shouldBeInstanceOf<PlanResult.Drafted>()
                // The plan draws a capability from each area.
                result.plan.nodesList.map { it.query.queryRef } shouldContain "listUnpaidInvoices"
                result.plan.nodesList.map { it.query.queryRef } shouldContain "wageByCostCenter"
                // Both areas' Shem context reached the planner prompt.
                val prompt = llm.capturedPrompts.first()
                prompt shouldContain "area ERP"
                prompt shouldContain "area HR"
                prompt shouldContain "wageByCostCenter"
            }
        }
    })
