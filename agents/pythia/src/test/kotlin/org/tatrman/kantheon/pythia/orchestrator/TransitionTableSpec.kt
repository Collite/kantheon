package org.tatrman.kantheon.pythia.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.tatrman.kantheon.pythia.v1.Status

/**
 * Stage 1.3 T1 — exhaustive transition-table pin (design §3.4 + PD-11). Asserts
 * every legal pair is accepted and every illegal pair throws; each AWAITING_* is
 * reachable only from its owning active phase, resumes to a defined next status,
 * and maps to exactly one control endpoint; terminals accept no outbound.
 */
class TransitionTableSpec :
    StringSpec({

        // The authoritative legal set, declared independently of the implementation.
        val expectedLegal: Set<Pair<Status, Status>> =
            setOf(
                Status.STATUS_SUBMITTED to Status.STATUS_RESOLVING,
                Status.STATUS_SUBMITTED to Status.STATUS_FAILED,
                Status.STATUS_RESOLVING to Status.STATUS_AWAITING_RESOLUTION_INPUT,
                Status.STATUS_RESOLVING to Status.STATUS_PLANNING,
                Status.STATUS_RESOLVING to Status.STATUS_FAILED,
                Status.STATUS_AWAITING_RESOLUTION_INPUT to Status.STATUS_RESOLVING,
                Status.STATUS_AWAITING_RESOLUTION_INPUT to Status.STATUS_HALTED,
                Status.STATUS_AWAITING_RESOLUTION_INPUT to Status.STATUS_FAILED,
                Status.STATUS_PLANNING to Status.STATUS_AWAITING_PLAN_APPROVAL,
                Status.STATUS_PLANNING to Status.STATUS_EXECUTING,
                Status.STATUS_PLANNING to Status.STATUS_FAILED,
                Status.STATUS_AWAITING_PLAN_APPROVAL to Status.STATUS_EXECUTING,
                Status.STATUS_AWAITING_PLAN_APPROVAL to Status.STATUS_PLANNING,
                Status.STATUS_AWAITING_PLAN_APPROVAL to Status.STATUS_HALTED,
                Status.STATUS_EXECUTING to Status.STATUS_AWAITING_USER_INPUT,
                Status.STATUS_EXECUTING to Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL,
                Status.STATUS_EXECUTING to Status.STATUS_AWAITING_BUDGET_DECISION,
                Status.STATUS_EXECUTING to Status.STATUS_SYNTHESIZING,
                Status.STATUS_EXECUTING to Status.STATUS_FAILED,
                Status.STATUS_EXECUTING to Status.STATUS_HALTED,
                Status.STATUS_EXECUTING to Status.STATUS_INCONCLUSIVE,
                Status.STATUS_AWAITING_USER_INPUT to Status.STATUS_EXECUTING,
                Status.STATUS_AWAITING_USER_INPUT to Status.STATUS_HALTED,
                Status.STATUS_AWAITING_USER_INPUT to Status.STATUS_FAILED,
                Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL to Status.STATUS_EXECUTING,
                Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL to Status.STATUS_HALTED,
                Status.STATUS_AWAITING_BUDGET_DECISION to Status.STATUS_EXECUTING,
                Status.STATUS_AWAITING_BUDGET_DECISION to Status.STATUS_SYNTHESIZING,
                Status.STATUS_AWAITING_BUDGET_DECISION to Status.STATUS_HALTED,
                Status.STATUS_SYNTHESIZING to Status.STATUS_DONE,
                Status.STATUS_SYNTHESIZING to Status.STATUS_HALTED,
                Status.STATUS_SYNTHESIZING to Status.STATUS_INCONCLUSIVE,
            )

        val realStatuses = Status.entries.filter { it != Status.STATUS_UNSPECIFIED && it != Status.UNRECOGNIZED }

        "every (from,to) pair matches the authoritative legal set" {
            for (from in realStatuses) {
                for (to in realStatuses) {
                    if (from == to) continue
                    val legal = TransitionTable.isLegal(from, to)
                    val expected = (from to to) in expectedLegal
                    if (legal != expected) {
                        throw AssertionError("transition $from → $to: impl=$legal expected=$expected")
                    }
                }
            }
        }

        "validate accepts legal and throws IllegalTransition on illegal" {
            TransitionTable.validate(Status.STATUS_SUBMITTED, Status.STATUS_RESOLVING)
            shouldThrow<IllegalTransition> {
                TransitionTable.validate(Status.STATUS_SUBMITTED, Status.STATUS_DONE)
            }
        }

        "terminal statuses accept no outbound transition" {
            for (terminal in TransitionTable.TERMINALS) {
                for (to in realStatuses) {
                    TransitionTable.isLegal(terminal, to) shouldBe false
                }
            }
        }

        "each AWAITING_* is reachable from exactly its owning active phase" {
            // AWAITING_RESOLUTION_INPUT only from RESOLVING
            realStatuses.filter { TransitionTable.isLegal(it, Status.STATUS_AWAITING_RESOLUTION_INPUT) } shouldBe
                listOf(Status.STATUS_RESOLVING)
            // AWAITING_PLAN_APPROVAL only from PLANNING
            realStatuses.filter { TransitionTable.isLegal(it, Status.STATUS_AWAITING_PLAN_APPROVAL) } shouldBe
                listOf(Status.STATUS_PLANNING)
            // the three EXECUTING-owned pauses
            realStatuses.filter { TransitionTable.isLegal(it, Status.STATUS_AWAITING_USER_INPUT) } shouldBe
                listOf(Status.STATUS_EXECUTING)
            realStatuses.filter { TransitionTable.isLegal(it, Status.STATUS_AWAITING_PLAN_REVISION_APPROVAL) } shouldBe
                listOf(Status.STATUS_EXECUTING)
            realStatuses.filter { TransitionTable.isLegal(it, Status.STATUS_AWAITING_BUDGET_DECISION) } shouldBe
                listOf(Status.STATUS_EXECUTING)
        }

        "the five AWAITING_* each map to exactly one resume endpoint" {
            TransitionTable.AWAITING shouldHaveSize 5
            TransitionTable.RESUME_ENDPOINT.keys shouldBe TransitionTable.AWAITING
            TransitionTable.RESUME_ENDPOINT[Status.STATUS_AWAITING_BUDGET_DECISION] shouldBe "/budget-decision"
            TransitionTable.RESUME_ENDPOINT[Status.STATUS_AWAITING_PLAN_APPROVAL] shouldBe "/approve-plan"
        }
    })
