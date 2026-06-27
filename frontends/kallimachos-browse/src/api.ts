// Thin client for the kallimachos-mcp `library.*` tools (contracts §4). Each call
// is an MCP tool invocation forwarded to the warehouse; the caller's OBO bearer
// rides as the `bearer` arg so the RLS edge (P4 S4.2) scopes the result.

export interface Citation {
  sourceId: number;
  partId: number;
  pageId?: number;
  title: string;
  locator: string;
  sourceRef: string;
}

export interface Page {
  id: number;
  kind: string;
  title: string;
  contentMd: string;
}

export interface GraphNode {
  id: number;
  kind: string;
  title: string;
}
export interface GraphEdge {
  from: number;
  to: number;
  kind: string;
}

async function callTool<T>(tool: string, args: Record<string, unknown>, bearer: string): Promise<T> {
  const res = await fetch(`/library/mcp`, {
    method: "POST",
    headers: { "Content-Type": "application/json", Authorization: `Bearer ${bearer}` },
    body: JSON.stringify({
      jsonrpc: "2.0",
      id: 1,
      method: "tools/call",
      params: { name: tool, arguments: { ...args, bearer } },
    }),
  });
  if (!res.ok) throw new Error(`${tool} failed: ${res.status}`);
  const body = await res.json();
  // MCP CallToolResult → the store's JSON rides in the first TextContent.
  const text = body?.result?.content?.[0]?.text;
  return (text ? JSON.parse(text) : body) as T;
}

export const library = {
  getPage: (id: number, bearer: string) => callTool<Page>("library.getPage", { id: String(id) }, bearer),
  traverse: (fromNodeId: number, hops: number, bearer: string) =>
    callTool<{ nodes: GraphNode[]; edges: GraphEdge[] }>("library.traverse", { fromNodeId, hops }, bearer),
  getContext: (notebookId: string, query: string, bearer: string) =>
    callTool<{ chunks: { text: string; citation: Citation }[]; grounded: boolean }>(
      "library.getContext",
      { notebookId, query },
      bearer,
    ),
};
