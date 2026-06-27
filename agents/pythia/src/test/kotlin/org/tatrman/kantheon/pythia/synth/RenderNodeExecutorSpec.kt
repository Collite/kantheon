package org.tatrman.kantheon.pythia.synth

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import org.tatrman.kantheon.envelope.v1.BlockRole
import org.tatrman.kantheon.envelope.v1.FormatKind
import org.tatrman.kantheon.pythia.executor.NodeContext
import org.tatrman.kantheon.pythia.handles.HandleTable
import org.tatrman.kantheon.pythia.plan.ScriptedPromptExecutor
import org.tatrman.kantheon.pythia.v1.PlanNode
import org.tatrman.kantheon.pythia.v1.RenderNode

/**
 * Stage 2.4 T1/T3 — RenderNode: TABLE wraps the input handle's rows into a Block;
 * NARRATIVE_FRAGMENT issues a per-fragment CHEAP call; CHART degrades gracefully.
 */
class RenderNodeExecutorSpec :
    StringSpec({

        fun render(
            id: String,
            kind: RenderNode.RenderKind,
            inputs: List<String> = emptyList(),
            caption: String = "",
        ): PlanNode =
            PlanNode
                .newBuilder()
                .setNodeId(id)
                .setRender(
                    RenderNode
                        .newBuilder()
                        .setKind(kind)
                        .setBlockRole(BlockRole.PRIMARY)
                        .addAllInputHandleIds(inputs)
                        .setCaption(caption),
                ).build()

        "TABLE renders the input handle rows into a table Block" {
            runTest {
                val handles = HandleTable()
                handles.putSnapshot("H1", Json.parseToJsonElement("""[{"id":1},{"id":2}]""") as JsonArray)
                val executor = RenderNodeExecutor(ScriptedPromptExecutor(emptyList()))
                executor.execute(
                    render("N1", RenderNode.RenderKind.RENDER_TABLE, listOf("H1"), "Decline"),
                    NodeContext(handles, ""),
                )
                val block = handles.blocks().single()
                block.format.kind shouldBe FormatKind.TABLE
                block.caption shouldBe "Decline"
                block.contentJson shouldContain "\"id\":1"
            }
        }

        "NARRATIVE_FRAGMENT issues a CHEAP call and yields a text Block" {
            runTest {
                val handles = HandleTable()
                val exec = ScriptedPromptExecutor(listOf("Revenue fell across SMB customers."))
                RenderNodeExecutor(exec).execute(
                    render("N2", RenderNode.RenderKind.RENDER_NARRATIVE_FRAGMENT),
                    NodeContext(handles, "", locale = "en"),
                )
                exec.callCount shouldBe 1
                handles.blocks().single().text shouldBe "Revenue fell across SMB customers."
            }
        }

        "CHART with no input series degrades to a placeholder Block + a Rule-6 warning (Stage 4.2)" {
            runTest {
                val handles = HandleTable()
                val result =
                    RenderNodeExecutor(ScriptedPromptExecutor(emptyList()))
                        .execute(render("N3", RenderNode.RenderKind.RENDER_CHART), NodeContext(handles, ""))
                result.warnings.single() shouldContain "no input series"
                handles
                    .blocks()
                    .single()
                    .format.kind shouldBe FormatKind.CHART
            }
        }
    })
