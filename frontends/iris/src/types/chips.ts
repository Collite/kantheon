// FE-local flat chip view-model for the suggested-chip strip (Phase 2 Stage 2.2).
//
// envelope/v1 `Chip` is a oneof (prompt / routing / investigate); the strip
// renders the `prompt` arm. This flat shape is UI state (built inline for
// static/suggested chips too), NOT the wire contract — so it stays FE-local.
import type { Chip } from '@kantheon/envelope-ts'

export interface SuggestedChip {
  display: string
  prompt: string
  source: string
  patternId?: string
  prefilledArgsJson?: string
}

/** Extract the flat PromptChip arms from a list of envelope/v1 oneof chips. */
export function promptChipsOf(chips: Chip[] | undefined): SuggestedChip[] {
  if (!chips) return []
  return chips.flatMap((c) =>
    c.prompt
      ? [
          {
            display: c.prompt.display,
            prompt: c.prompt.prompt,
            source: c.prompt.source,
            patternId: c.prompt.patternId,
            prefilledArgsJson: c.prompt.prefilledArgsJson,
          },
        ]
      : [],
  )
}
