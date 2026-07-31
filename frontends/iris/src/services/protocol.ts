// `/protocol` — asks iris-bff to render the session's execution path as a
// markdown document (PT arc, contracts §3.1).
//
// Thin by design: build the request, attach the shared auth headers, throw on a
// non-2xx with the status attached so the caller can pick the right toast. No
// toasting and no store writes here — the command handler owns both.
import { config } from '@/config'
import { authHeaders } from '@/services/authHeaders'
import { toAbsoluteOrigin } from '@/services/baseUrl'
import type { ProtocolScope } from '@/components/chat/slashCommands'

export interface ProtocolResponse {
  protocolId: string
  /** S-8 title: `Protocol — <session title> — <scope>`. */
  title: string
  /** envelope/v1 FormatEnvelope, `format.kind = MARKDOWN`. */
  envelope: Record<string, unknown>
}

/** Carries the HTTP status so the handler can map 400/403/404 to distinct toasts. */
export class ProtocolRequestError extends Error {
  constructor(
    message: string,
    readonly status: number,
  ) {
    super(message)
    this.name = 'ProtocolRequestError'
  }
}

export async function requestProtocol(
  sessionId: string,
  scope: ProtocolScope,
): Promise<ProtocolResponse> {
  const base = toAbsoluteOrigin(config.bff.baseUrl)
  const url = `${base}/v1/session/${encodeURIComponent(sessionId)}/protocol`
  const res = await fetch(url, {
    method: 'POST',
    headers: await authHeaders({ 'Content-Type': 'application/json' }),
    body: JSON.stringify({ scope }),
  })
  if (!res.ok) {
    throw new ProtocolRequestError(`protocol failed: HTTP ${res.status}`, res.status)
  }
  return (await res.json()) as ProtocolResponse
}
