// @vitest-environment jsdom
import { describe, it, expect, beforeEach } from 'vitest'
import services from '../../public/services.json'
import cs from '../i18n/locales/cs.json'
import de from '../i18n/locales/de.json'
import en from '../i18n/locales/en.json'
import hu from '../i18n/locales/hu.json'
import sk from '../i18n/locales/sk.json'

/**
 * Stage 5.4 rebrand guard (mocked unit tier — no live cluster). Proves the landing page is
 * on-brand in all five locales, the dispatcher catalog targets the kantheon estate (and carries
 * no legacy ai-platform / erp-sql tile), and the runtime config resolves links + the health
 * roll-up URL from the injected APP_CONFIG. In-browser e2e on K3s is the integration suite's job.
 */
describe('landing rebrand', () => {
  it('renders the Kantheon brand title in every locale', () => {
    for (const locale of [cs, de, en, hu, sk]) {
      expect(locale.header.title).toBe('Kantheon')
      expect(JSON.stringify(locale)).not.toMatch(/DF.?Partner|ai-platform/i)
    }
  })

  it('the dispatcher catalog targets the kantheon estate, no legacy tiles', () => {
    const techs: string[] = services.categories.flatMap(
      (c: { services: { tech: string }[] }) => c.services.map((s) => s.tech),
    )
    // Kantheon constellation + fabric-infra are present.
    expect(techs).toEqual(
      expect.arrayContaining([
        'ariadne',
        'theseus',
        'argos',
        'kyklop',
        'brontes',
        'capabilities-mcp',
        'whois',
        'backstage',
        'kantheon-postgres',
        'grafana',
      ]),
    )
    // No legacy ai-platform / erp-sql tile survives the re-point.
    expect(techs.some((t) => t.startsWith('sql-'))).toBe(false)
    for (const legacy of ['metadata', 'fuzzy-matcher', 'meta-mcp', 'fuzzy-mcp', 'erp-data-mcp', 'llm-gateway']) {
      expect(techs).not.toContain(legacy)
    }
    // Every tile's `tech` is a non-empty key (so the health roll-up can index it).
    for (const t of techs) expect(t.length).toBeGreaterThan(0)
  })

  describe('runtime config resolves from APP_CONFIG', () => {
    beforeEach(() => {
      window.APP_CONFIG = {
        KEYCLOAK_URL: 'https://keycloak.kantheon.example',
        KEYCLOAK_REALM: 'kantheon',
        KEYCLOAK_CLIENT_ID: 'landing',
        LINK_GRAFANA: 'https://grafana.kantheon.example',
        LINK_DEV_PORTAL: 'https://backstage.kantheon.example',
        HEALTH_URL: 'https://health.kantheon.example',
      }
    })

    it('links + health URL come from the injected kantheon hosts', async () => {
      const { config } = await import('../config')
      expect(config.links.grafana).toBe('https://grafana.kantheon.example')
      expect(config.links.devPortal).toBe('https://backstage.kantheon.example')
      expect(config.healthUrl).toBe('https://health.kantheon.example')
      expect(config.keycloak.realm).toBe('kantheon')
    })
  })
})
