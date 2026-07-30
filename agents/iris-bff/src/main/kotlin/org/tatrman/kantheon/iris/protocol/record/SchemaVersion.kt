package org.tatrman.kantheon.iris.protocol.record

import org.tatrman.kantheon.protocol.v1.ProtocolRecord

/**
 * Version stamp carried by every persisted [ProtocolRecord] and every assembled
 * `ProtocolDocument` (contracts §1: `"protocol/v1.<minor>"`).
 *
 * **Policy.**
 * - **Minor bumps are additive-only** — a new optional field, a new section key,
 *   a widened enum. Readers built against an older minor must still parse a
 *   newer record without error, which is what proto3 unknown-field retention
 *   buys us; [ProtocolRecordJson] parses with `ignoringUnknownFields()` so the
 *   JSONB columns inherit the same tolerance.
 * - **Any breaking change requires a new package** — `protocol/v2` — plus a
 *   contracts.md amendment. Never repurpose a field number or change a field's
 *   meaning under `v1`.
 *
 * The stamp exists so the assembler can tell *what it is reading* years after
 * the writer shipped: records outlive the code that wrote them (PT-4), and the
 * golden-fixture corpus (PT-22) uses the stamp to detect drift.
 */
object SchemaVersion {
    const val CURRENT: String = "protocol/v1.0"

    /**
     * Return [record] carrying [CURRENT]. Called on every write path — the
     * writer's opinion of its own version is not trusted, because a record can
     * be built by a caller (or a fixture) that predates the running code.
     */
    fun stamp(record: ProtocolRecord): ProtocolRecord =
        if (record.schemaVersion == CURRENT) record else record.toBuilder().setSchemaVersion(CURRENT).build()
}
