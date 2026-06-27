// Slash-command parser tests — locks the `/new` command and the parser
// contract the chat input relies on (FE-7).
import { describe, expect, it } from 'vitest'
import {
  SLASH_COMMANDS,
  filterSlashCommands,
  parseSlashInput,
} from '@/components/chat/slashCommands'

describe('SLASH_COMMANDS registry', () => {
  it('exposes both /new and the Stage 2.3 server-side /reset', () => {
    const names = SLASH_COMMANDS.map((c) => c.name)
    expect(names).toContain('new')
    // /reset is back as a distinct command — server-side clear with snapshot-undo
    // (vs the FE-only /clear), not the old alias for /new.
    expect(names).toContain('reset')
  })

  it('maps /new to its description key', () => {
    const spec = SLASH_COMMANDS.find((c) => c.name === 'new')
    expect(spec).toBeDefined()
    expect(spec?.descriptionKey).toBe('slash.new')
  })

  it('maps /reset to a server request command', () => {
    const spec = SLASH_COMMANDS.find((c) => c.name === 'reset')
    expect(spec?.kind).toBe('request')
    expect(spec?.descriptionKey).toBe('slash.reset')
  })
})

describe('parseSlashInput', () => {
  it('resolves /new to the new-session command', () => {
    const parsed = parseSlashInput('/new')
    expect(parsed?.spec?.name).toBe('new')
  })

  it('is case-insensitive on the command name', () => {
    expect(parseSlashInput('/NEW')?.spec?.name).toBe('new')
  })

  it('returns null for plain text so it falls through to a normal message', () => {
    expect(parseSlashInput('hello there')).toBeNull()
  })

  it('returns a null spec for an unknown /command', () => {
    const parsed = parseSlashInput('/bogus')
    expect(parsed).not.toBeNull()
    expect(parsed?.spec).toBeNull()
  })
})

describe('/refresh command', () => {
  it('is registered as an arg-accepting request command', () => {
    const spec = SLASH_COMMANDS.find((c) => c.name === 'refresh')
    expect(spec).toBeDefined()
    expect(spec?.kind).toBe('request')
    expect(spec?.acceptsArg).toBe(true)
  })

  it('parses /refresh with no arg', () => {
    const parsed = parseSlashInput('/refresh')
    expect(parsed?.spec?.name).toBe('refresh')
    expect(parsed?.arg).toBeUndefined()
  })

  it('parses /refresh <service> and captures the arg', () => {
    const parsed = parseSlashInput('/refresh query-runner')
    expect(parsed?.spec?.name).toBe('refresh')
    expect(parsed?.arg).toBe('query-runner')
  })

  it('matches /refresh on a partial prefix', () => {
    expect(filterSlashCommands('/ref').map((c) => c.name)).toContain('refresh')
  })
})

describe('filterSlashCommands', () => {
  it('matches /new on a partial prefix', () => {
    const names = filterSlashCommands('/n').map((c) => c.name)
    expect(names).toContain('new')
  })

  it('returns the full registry for a bare slash', () => {
    expect(filterSlashCommands('/')).toEqual(SLASH_COMMANDS)
  })
})
