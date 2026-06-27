// Artifact wire types (Iris Phase 4 Stage 4.2 — PD-6 pins & dashboards).
//
// Mirrors the iris-bff ArtifactDto (contracts §2.8 / §3.3). JSON columns arrive
// as nested JSON (the BFF surfaces them parsed, not as escaped strings), so the
// envelope is consumed via `@kantheon/envelope-ts` `FormatEnvelope.fromJSON`.
export type ArtifactKind = 'pin' | 'dashboard'

export interface ArtifactDto {
  artifactId: string
  kind: ArtifactKind
  name: string
  agentId?: string
  envelope?: unknown
  provenance?: unknown
  appliedContext?: unknown
  displayState?: unknown
  params?: unknown
  refreshMode: string
  paramMode?: string
  templateId?: string
  memberIds: string[]
  layout?: unknown
  refreshedAt?: string
  refreshError?: string
  createdAt: string
  updatedAt: string
}

export interface CreatePinRequest {
  turnId: string
  bubbleId: string
  name: string
}

export interface CreateDashboardRequest {
  name: string
  memberIds?: string[]
  layoutJson?: string
  templateId?: string
  paramsJson?: string
  refreshMode?: string
}

export interface ArtifactPatch {
  name?: string
  paramsJson?: string
  layoutJson?: string
  memberIds?: string[]
  refreshMode?: string
}
