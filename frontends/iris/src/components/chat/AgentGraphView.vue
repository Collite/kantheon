<template>
  <div ref="containerRef" class="agent-graph-container">
    <VueFlow
      v-model:nodes="graphNodes"
      v-model:edges="graphEdges"
      :node-types="nodeTypes"
      :fit-view-on-init="true"
      :nodes-draggable="true"
      :nodes-connectable="false"
      :elements-selectable="false"
      :pan-on-drag="true"
      :zoom-on-scroll="false"
      @pane-ready="onPaneReady"
      @nodes-change="onNodesChange"
    >
      <Background pattern-color="var(--p-surface-300)" :gap="14" />
    </VueFlow>
  </div>
</template>

<script setup lang="ts">
import { ref, watch, onMounted, onBeforeUnmount, markRaw } from 'vue'
import {
  VueFlow,
  useVueFlow,
  MarkerType,
  type NodeChange,
  type NodeTypesObject,
} from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import dagre from 'dagre'
import { config } from '@/config'
import { authHeaders } from '@/services/authHeaders'
import { useAgentSession } from '@/composables/useAgentSession'
import LogicNode from '@/components/graph/LogicNode.vue'
import McpNode from '@/components/graph/McpNode.vue'

// Phase 2: layout reflowed to wide-and-short.
//   - Logic nodes flow left → right via dagre `LR`.
//   - MCP server nodes sit in a row below the logic strip (off the dagre flow
//     because tool-call edges are dynamic).
//   - Active / traversed visuals use Aura red & surface tokens.
//   - Re-fit on container resize (e.g. user dragging the dockview Graph
//     splitter) via ResizeObserver, debounced.
const NODE_W = 130
const NODE_H = 38
const NODE_SEP = 24      // horizontal gap between same-rank logic nodes
const RANK_SEP = 70      // gap between logic ranks
const MCP_GAP_Y = 60     // vertical distance between logic strip and MCP row
const MCP_SPACING_X = 160

const DAGRE_EDGE_STROKE = 'var(--p-surface-400)'
const DAGRE_EDGE_STROKE_TRAVERSED = 'var(--p-primary-500)'
const TOOL_EDGE_STROKE = 'var(--p-primary-500)'
// Dimmed red used for completed tool-call edges that persist after the
// tool finishes — matches the dimmed-red node styling.
const TOOL_EDGE_STROKE_COMPLETED = 'var(--p-primary-300)'

const props = defineProps<{
  currentNode: string | null
  currentTool: { tool_name: string; server: string } | null
  traversedNodes: string[]
}>()

const containerRef = ref<HTMLDivElement | null>(null)
const graphNodes = ref<any[]>([])
const graphEdges = ref<any[]>([])
const { fitView } = useVueFlow()

// VueFlow's `NodeTypesObject` has a stricter signature than our SFC props
// expose; markRaw + a deliberate cast is the documented escape hatch when the
// renderer only reads the data/label fields anyway.
const nodeTypes = {
  logic: markRaw(LogicNode),
  mcp: markRaw(McpNode),
} as unknown as NodeTypesObject

const layoutGraph = (nodes: any[], edges: any[]) => {
  const dagreGraph = new dagre.graphlib.Graph()
  dagreGraph.setDefaultEdgeLabel(() => ({}))
  dagreGraph.setGraph({ rankdir: 'LR', nodesep: NODE_SEP, ranksep: RANK_SEP })

  const mcpNodes = nodes.filter((n) => n.type === 'mcp')
  const logicNodes = nodes.filter((n) => n.type !== 'mcp')

  logicNodes.forEach((node) => {
    dagreGraph.setNode(node.id, { width: NODE_W, height: NODE_H })
  })

  edges.forEach((edge) => {
    dagreGraph.setEdge(edge.source, edge.target)
  })

  dagre.layout(dagreGraph)

  let minX = Infinity
  let maxX = -Infinity
  let maxY = -Infinity

  const layoutedLogic = logicNodes.map((node) => {
    const pos = dagreGraph.node(node.id)
    if (pos.x < minX) minX = pos.x
    if (pos.x > maxX) maxX = pos.x
    if (pos.y > maxY) maxY = pos.y
    return {
      ...node,
      type: 'logic',
      position: { x: pos.x - NODE_W / 2, y: pos.y - NODE_H / 2 },
      style: { width: `${NODE_W}px`, height: `${NODE_H}px` },
    }
  })

  // MCP servers: a row below the logic strip, centred.
  const centerX = isFinite(minX) && isFinite(maxX) ? (minX + maxX) / 2 : 0
  const totalMcpWidth = (mcpNodes.length - 1) * MCP_SPACING_X
  const mcpStartX = centerX - totalMcpWidth / 2
  const mcpY = (isFinite(maxY) ? maxY : 0) + MCP_GAP_Y

  const layoutedMcp = mcpNodes.map((node, idx) => ({
    ...node,
    type: 'mcp',
    position: { x: mcpStartX + idx * MCP_SPACING_X - NODE_W / 2, y: mcpY },
    style: { width: `${NODE_W}px`, height: `${NODE_H}px` },
  }))

  return [...layoutedLogic, ...layoutedMcp]
}

const updateNodeStyles = () => {
  graphNodes.value = graphNodes.value.map((n) => {
    const isLogicActive = n.type !== 'mcp' && n.id === props.currentNode
    const isMcp = n.type === 'mcp'
    const isToolActive = isMcp && !!props.currentTool && n.id === props.currentTool.server
    const isTraversed = props.traversedNodes.includes(n.id)

    return {
      ...n,
      data: {
        ...n.data,
        label: n.label ?? n.data?.label ?? n.id,
        active: isMcp ? isToolActive : isLogicActive,
        traversed: isTraversed,
      },
    }
  })
}

// Set of MCP node ids derived from the loaded graph. Populated in
// onMounted; used to filter `traversedNodes` down to logic-only steps for
// the dagre consecutive-pair check (otherwise the interleaved MCP visits
// break every adjacency, e.g. [erp_pattern_query, erp-data-mcp,
// local_pattern_query, ...] never matches a static edge).
const mcpNodeIds = ref<Set<string>>(new Set())

const logicHistory = (history: string[]): string[] =>
  history.filter((id) => !mcpNodeIds.value.has(id))

const isEdgeTraversed = (source: string, target: string): boolean => {
  const history = logicHistory(props.traversedNodes)
  for (let i = 0; i < history.length - 1; i++) {
    if (history[i] === source && history[i + 1] === target) return true
  }
  return false
}

// Keep tool-call edges (id starts with `tool-`) untouched here — they own
// their styling through `_setToolEdgeState`. We only restyle the static
// dagre edges loaded from /v2/agent/graph.
const updateStaticEdges = () => {
  graphEdges.value = graphEdges.value.map((e) => {
    if (e.id.startsWith('tool-')) return e
    const traversed = isEdgeTraversed(e.source, e.target)
    const stroke = traversed ? DAGRE_EDGE_STROKE_TRAVERSED : DAGRE_EDGE_STROKE
    const strokeWidth = traversed ? 2.5 : 1.25
    return {
      ...e,
      animated: !traversed,
      style: { stroke, strokeWidth },
      markerEnd: { type: MarkerType.ArrowClosed, color: stroke, width: 16, height: 16 },
    }
  })
}

let refitTimer: number | null = null
const scheduleRefit = () => {
  if (refitTimer != null) window.clearTimeout(refitTimer)
  refitTimer = window.setTimeout(() => {
    refitTimer = null
    if (graphNodes.value.length > 0) {
      fitView({ padding: 0.15, duration: 200 })
    }
  }, 120)
}

const onPaneReady = () => {
  fitView({ padding: 0.15 })
}

// VueFlow emits a stream of `position` changes during a drag (one per frame),
// then a final one with `dragging: false` on release. Refit only on release —
// refitting mid-drag would yank the canvas out from under the user.
const onNodesChange = (changes: NodeChange[]) => {
  if (changes.some((c) => c.type === 'position' && c.dragging === false)) {
    scheduleRefit()
  }
}

let resizeObserver: ResizeObserver | null = null

onMounted(async () => {
  // Refit when the graph container resizes — covers dockview splitter drags,
  // window resizes, and Tabs/Graph collapse-toggle alike.
  if (containerRef.value && 'ResizeObserver' in window) {
    resizeObserver = new ResizeObserver(() => scheduleRefit())
    resizeObserver.observe(containerRef.value)
  }

  try {
    // Resolve the active agent's origin (graph topology is identical across
    // Golem instances, but stay consistent with the selected agent).
    const session = useAgentSession()
    const agent = config.golemAgents.find((a) => a.id === session.agentKey.value)
    let baseUrl = agent?.baseUrl || config.golem.baseUrl || 'erp-agent.dfpartner.cz'
    if (!baseUrl.startsWith('http')) {
      baseUrl = `${window.location.protocol}//${baseUrl}`
    }

    const response = await fetch(`${baseUrl}/v2/agent/graph`, { headers: await authHeaders() })
    if (!response.ok) return

    const data = await response.json()

    const vueFlowNodes = data.nodes.map((n: any) => ({
      id: n.id,
      label: n.label || n.id,
      type: n.type === 'mcp' ? 'mcp' : 'logic',
      data: { label: n.label || n.id },
    }))
    mcpNodeIds.value = new Set(
      data.nodes.filter((n: any) => n.type === 'mcp').map((n: any) => String(n.id)),
    )

    const vueFlowEdges = data.edges.map((e: any, i: number) => ({
      id: `e-${e.source}-${e.target}-${i}`,
      source: e.source,
      target: e.target,
      animated: true,
      style: { stroke: DAGRE_EDGE_STROKE, strokeWidth: 1.25 },
      markerEnd: { type: MarkerType.ArrowClosed, color: DAGRE_EDGE_STROKE, width: 16, height: 16 },
    }))

    graphNodes.value = layoutGraph(vueFlowNodes, vueFlowEdges)
    graphEdges.value = vueFlowEdges
    updateNodeStyles()
  } catch (e) {
    console.error('Failed to load graph', e)
  }
})

onBeforeUnmount(() => {
  if (resizeObserver) {
    resizeObserver.disconnect()
    resizeObserver = null
  }
  if (refitTimer != null) {
    window.clearTimeout(refitTimer)
    refitTimer = null
  }
})

watch(() => props.currentNode, () => {
  updateNodeStyles()
})

watch(() => props.traversedNodes, () => {
  updateNodeStyles()
  updateStaticEdges()
}, { deep: true })

// Tool-call edges all share the `tool-` id prefix. We distinguish active
// from completed via the edge's `data.state` field rather than mutating the
// id, which keeps VueFlow's edge identity stable across the active→
// completed transition (changing ids mid-flight makes VueFlow re-mount the
// edge and can drop the visual).
let toolEdgeCounter = 0
const _toolEdgeId = (source: string, target: string) =>
  `tool-${source}-${target}-${(toolEdgeCounter += 1)}`

const _toolEdgeStyle = (active: boolean) => {
  if (active) {
    return {
      animated: true,
      style: { stroke: TOOL_EDGE_STROKE, strokeWidth: 2.5 },
      markerEnd: {
        type: MarkerType.ArrowClosed,
        color: TOOL_EDGE_STROKE,
        width: 16,
        height: 16,
      },
      labelStyle: { fill: TOOL_EDGE_STROKE, fontWeight: 600, fontSize: 11 },
      labelBgStyle: { fill: '#fff', fillOpacity: 0.92, stroke: 'var(--p-surface-300)' },
    }
  }
  return {
    animated: false,
    style: { stroke: TOOL_EDGE_STROKE_COMPLETED, strokeWidth: 2 },
    markerEnd: {
      type: MarkerType.ArrowClosed,
      color: TOOL_EDGE_STROKE_COMPLETED,
      width: 14,
      height: 14,
    },
    labelStyle: { fill: TOOL_EDGE_STROKE_COMPLETED, fontWeight: 500, fontSize: 10 },
    labelBgStyle: { fill: '#fff', fillOpacity: 0.92, stroke: 'var(--p-surface-300)' },
  }
}

// Demote any active tool-edge to completed (in place — same id, swapped
// styling). Used both when a tool ends and when the next tool starts.
const _completeActiveToolEdges = (edges: any[]) =>
  edges.map((e) => {
    if (!e.id.startsWith('tool-')) return e
    if (e.data?.state !== 'active') return e
    return {
      ...e,
      data: { ...e.data, state: 'completed' },
      ..._toolEdgeStyle(false),
    }
  })

watch(() => props.currentTool, (tool) => {
  updateNodeStyles()

  // Preserve any in-flight tool edge as "completed" before reacting to the
  // new tool — handles tool-A → tool-B chains within one logic node.
  let next = _completeActiveToolEdges(graphEdges.value)

  if (tool && props.currentNode) {
    next = [
      ...next,
      {
        id: _toolEdgeId(props.currentNode, tool.server),
        source: props.currentNode,
        target: tool.server,
        sourceHandle: 'mcp-out',
        label: tool.tool_name,
        data: { state: 'active', tool: tool.tool_name },
        ..._toolEdgeStyle(true),
      },
    ]
  }
  graphEdges.value = next
}, { deep: true })

// New turn = traversedNodes cleared by useAgentSession.sendMessage. Drop
// every tool edge (active or completed) so the graph starts fresh. Static
// dagre edges revert via updateStaticEdges() — they drop out of the
// traversed set when the array empties.
watch(() => props.traversedNodes.length, (newLen, oldLen) => {
  if (newLen === 0 && (oldLen ?? 0) > 0) {
    graphEdges.value = graphEdges.value.filter((e) => !e.id.startsWith('tool-'))
  }
})
</script>

<style scoped>
.agent-graph-container {
  width: 100%;
  height: 100%;
  background: var(--p-surface-50);
  border-radius: 0;
  border: none;
  overflow: hidden;
}

.agent-graph-container :deep(.vue-flow__node) {
  /* Strip the default vue-flow node chrome — our custom node components
     handle their own background, border, and shadow. */
  padding: 0;
  border: none;
  background: transparent;
  box-shadow: none;
}

.agent-graph-container :deep(.vue-flow__edge-path) {
  transition: stroke 200ms ease;
}

.agent-graph-container :deep(.vue-flow__handle) {
  width: 6px;
  height: 6px;
  background: var(--p-surface-400);
  border: none;
}
</style>
