package org.tatrman.pinakes.pipeline

import org.tatrman.pinakes.v1.StageKind

/**
 * The stage library (architecture §7) — the registry of concrete [Stage]s a
 * pipeline composes its DAG from. extract · classify · chunk (head) · embed ·
 * compile · link · resolve · load (conformed tail). compile/link/resolve are
 * stubs at S3.1; their real bodies land in S3.2.
 */
class StageLibrary(
    stages: List<Stage>,
) {
    private val byKind: Map<StageKind, Stage> = stages.associateBy { it.kind }

    fun stage(kind: StageKind): Stage = byKind[kind] ?: error("no stage registered for $kind")

    fun has(kind: StageKind): Boolean = byKind.containsKey(kind)
}
