import { bff } from '@/api/client'

export interface DraftAccepted {
  draft_id: string
  status: string
}

/**
 * Submit an async draft (contracts §3.2). The BFF assigns ids/status, returns
 * `202 { draft_id }`, and drives the commit off-thread — the caller watches
 * `/stream` (drafts store) for `DraftAck` / `BatchRowResult` / `DraftCommitted`.
 * `payload` is the form proto as proto-JSON (camelCase); it is stringified into
 * the `Draft.payload_json` field the committer parses back.
 */
export async function submitDraft(kind: string, payload: unknown): Promise<DraftAccepted> {
  return bff<DraftAccepted>('/drafts', {
    method: 'POST',
    body: { kind, payloadJson: JSON.stringify(payload) },
  })
}
