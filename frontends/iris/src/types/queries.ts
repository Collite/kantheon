// Phase 4 — types for the Queries pane / `/metadata/queries` endpoint.
// Mirrors the BE Pydantic shape in `src/api/metadata_routes.py`.

export type QueryCategory =
  | 'pattern'
  | 'sql_query'
  | 'procedure'
  | 'entity'
  | 'table'

export type QuerySource = 'local' | 'platform'

export interface QueryItem {
  id: string
  name: string
  pattern: string | null
  description: string
  category: QueryCategory
  source: QuerySource
}

export interface QueriesResponse {
  items: QueryItem[]
  warnings: string[]
}

export type QuerySourceFilter = QuerySource | 'both'
