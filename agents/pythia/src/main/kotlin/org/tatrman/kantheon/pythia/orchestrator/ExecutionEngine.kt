package org.tatrman.kantheon.pythia.orchestrator

import org.tatrman.kantheon.pythia.dataplane.EvidenceManager
import org.tatrman.kantheon.pythia.evaluate.HypothesisEvaluator
import org.tatrman.kantheon.pythia.executor.DagExecutor
import org.tatrman.kantheon.pythia.revise.PlanReviser
import org.tatrman.kantheon.pythia.suspicion.SuspicionClassifier
import org.tatrman.kantheon.pythia.suspicion.SuspicionPolicyHandler
import org.tatrman.kantheon.pythia.synth.Synthesizer

/**
 * Bundles the EXECUTING + SYNTHESIZING subsystems wired into the orchestrator from
 * Stage 2.4: the DAG executor (over a CompositeNodeExecutor), the rules-first
 * hypothesis evaluator, and the synthesizer. Stage 3.1 adds the suspicion
 * classifier + policy handler. Stage 4.1 adds the [evidenceManager] (evidence
 * persistence + GC at finalisation). Null in the orchestrator → the scripted stubs run.
 */
class ExecutionEngine(
    val dagExecutor: DagExecutor,
    val evaluator: HypothesisEvaluator,
    val synthesizer: Synthesizer,
    val suspicionClassifier: SuspicionClassifier? = null,
    val suspicionPolicy: SuspicionPolicyHandler? = null,
    /** The plan reviser drives the deepening loop (Stage 3.2/3.3); null → no deepening. */
    val reviser: PlanReviser? = null,
    /** Evidence persistence + GC at finalisation (Stage 4.1); null → no Charon persistence. */
    val evidenceManager: EvidenceManager? = null,
)
