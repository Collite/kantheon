// Slash command registry for the chat input (Phase 7).
//
// Side effects fall into three buckets:
//   - `client`: handled entirely on the FE (e.g. `/clear`, `/help`, `/export`).
//   - `pre-send-hint`: arms a one-shot flag that the next `/chat/stream`
//     request applies as a query param (`/format`, `/sql`).
//   - `request`: fires an HTTP call to the BE before any chat-stream work
//     (`/new` → rotates the local `thread_id` and re-bootstraps via
//     `POST /v2/session`, which starts a fresh agent session).
//
// The handler signatures stay small — they get a `SlashContext` with the
// stores and helpers they need, returning whether the input should be
// cleared after the command runs.

export type SlashKind = 'client' | 'pre-send-hint' | 'request'

export interface SlashCommandSpec {
  name: string                  // without the leading `/`
  /** vue-i18n key for the command's description (`slash.<name>`). */
  descriptionKey: string
  kind: SlashKind
  argHint?: string              // e.g. `<md|csv>` — shown after the command name
  // Some commands accept arguments; the parser splits the first token after
  // `name` and passes it as `arg`.
  acceptsArg?: boolean
}

export interface SlashRunResult {
  /** True → the input field should be cleared (e.g. handled, no follow-up). */
  clearInput: boolean
  /** True → close the slash popup. */
  closePopup: boolean
}

export const SLASH_COMMANDS: SlashCommandSpec[] = [
  { name: 'clear', descriptionKey: 'slash.clear', kind: 'client' },
  { name: 'new', descriptionKey: 'slash.new', kind: 'request' },
  // Stage 2.3: server-side clear with snapshot-undo (vs /clear which is FE-only).
  { name: 'reset', descriptionKey: 'slash.reset', kind: 'request' },
  {
    name: 'refresh',
    descriptionKey: 'slash.refresh',
    kind: 'request',
    argHint: '[service]',
    acceptsArg: true,
  },
  { name: 'help', descriptionKey: 'slash.help', kind: 'client' },
  {
    name: 'format',
    descriptionKey: 'slash.format',
    kind: 'pre-send-hint',
    argHint: '<kind>',
    acceptsArg: true,
  },
  {
    name: 'export',
    descriptionKey: 'slash.export',
    kind: 'client',
    argHint: '<md|csv>',
    acceptsArg: true,
  },
  { name: 'sql', descriptionKey: 'slash.sql', kind: 'pre-send-hint' },
]

/** Format hint argument vocabulary — accepted CLI strings for `/format <kind>`.
 *  These are user-facing arg tokens (a string surface), distinct from the
 *  envelope/v1 `FormatKind` enum used on the wire. */
export const FORMAT_HINT_KINDS: readonly string[] = ['plaintext', 'markdown', 'table', 'chart']

export interface ParsedSlash {
  spec: SlashCommandSpec | null
  arg?: string
}

/** Parse the input text and resolve to a command spec (when it starts with
 * `/`). Returns `null` when no leading slash; the caller falls through to
 * normal chat send. */
export const parseSlashInput = (raw: string): ParsedSlash | null => {
  if (!raw.startsWith('/')) return null
  const tokens = raw.slice(1).trim().split(/\s+/)
  const head = tokens[0] ?? ''
  if (!head) return { spec: null }
  const name = head.toLowerCase()
  const arg = tokens.length > 1 ? tokens.slice(1).join(' ') : undefined
  const spec = SLASH_COMMANDS.find((c) => c.name === name) ?? null
  return { spec, arg }
}

/** Filter the command list against a partial input ("/cl" → only `/clear`).
 * Empty filter returns all commands. */
export const filterSlashCommands = (raw: string): SlashCommandSpec[] => {
  if (!raw.startsWith('/')) return SLASH_COMMANDS
  const partial = (raw.slice(1).split(/\s+/)[0] ?? '').toLowerCase()
  if (!partial) return SLASH_COMMANDS
  return SLASH_COMMANDS.filter((c) => c.name.startsWith(partial))
}
