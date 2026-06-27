# hebe — architektura v1 (řešení + kontrakty)

Konkrétní kontrakty implementované ve verzi v1. Zatímco dokument [`hebe-architecture.md`](hebe-architecture.md) slouží jako obecný plán, tento dokument představuje **schéma zapojení** — rozhraní, schémata, životní cykly a taxonomie chyb.

Dokument je napsán tak, aby přispěvatel mohl převzít konkrétní část a implementovat ji bez nutnosti znovu rozhodovat o její podobě.

---

## Obsah

1. Rozvržení modulů (Gradle)
2. Verze a technologický stack
3. Kernel ABI (`api` modul) — Kotlin rozhraní
4. Plugin ABI (`plugin-api` modul) — Kotlin rozhraní
5. SQLite schéma + Flyway migrace
6. Rozvržení workspace
7. Schéma konfigurace (`config.toml`)
8. Úložiště tajemství (Secrets store)
9. Stavový automat Tool dispatcheru
10. Loop driver + kontrakt delegátů
11. Životní cyklus pluginů (integrace PF4J)
12. Distribuční flow OCI/ACR
13. Formát logu potvrzení (Receipts log)
14. Web console API (REST + SSE)
15. Nastavení transportu MCP serveru / klienta
16. Kontrakt kanálu Telegram
17. Kontrakt kanálu CLI
18. Scheduler + heartbeat
19. Boot sequence (deterministické pořadí inicializace)
20. Taxonomie chyb + pravidla pro jejich zpracování
21. Konvence pro logování a observabilitu
22. Bezpečnostní kontroly (konkrétní sekvence)

---

## 1. Rozvržení modulů (Gradle)
