// @vitest-environment jsdom
import { describe, it, expect } from 'vitest'
import { toAbsoluteOrigin } from '../baseUrl'

/**
 * Regression guard for the `https:///bff` join (mocked unit tier — no live cluster).
 *
 * Two call sites (AgentView's connection-status dot, AgentGraphView's topology fetch) used to
 * prefix `${window.location.protocol}//` onto anything not starting with 'http'. With the
 * deployed `VITE_BFF_BASE_URL=/bff` that produced `https:///bff/ready`, which the WHATWG URL
 * parser normalises to host `bff` — so the browser reported ERR_NAME_NOT_RESOLVED and the
 * connection dot sat permanently disconnected.
 */
describe('toAbsoluteOrigin', () => {
  it('leaves a root-relative path alone — the case that used to be corrupted', () => {
    expect(toAbsoluteOrigin('/bff')).toBe('/bff')
  })

  it('does not produce a URL whose host is a path segment', () => {
    // The exact failure: `${protocol}//${'/bff'}` + '/ready' → host 'bff'.
    const url = new URL(`${toAbsoluteOrigin('/bff')}/ready`, 'https://iris.example.cz')
    expect(url.host).toBe('iris.example.cz')
    expect(url.pathname).toBe('/bff/ready')
  })

  it('prefixes the page protocol onto a bare host', () => {
    expect(toAbsoluteOrigin('erp-agent.example.cz')).toBe(
      `${window.location.protocol}//erp-agent.example.cz`,
    )
  })

  it('passes absolute and protocol-relative origins through untouched', () => {
    expect(toAbsoluteOrigin('https://bff.example.cz')).toBe('https://bff.example.cz')
    expect(toAbsoluteOrigin('http://localhost:7410')).toBe('http://localhost:7410')
    expect(toAbsoluteOrigin('//bff.example.cz')).toBe('//bff.example.cz')
  })

  it('returns empty for an unset base rather than inventing an origin', () => {
    expect(toAbsoluteOrigin('')).toBe('')
  })
})
