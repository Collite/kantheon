package org.tatrman.kantheon.hebe.memory.search

import org.tatrman.kantheon.hebe.api.MemoryCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Query-construction unit tests for the Postgres candidate queries (planning-conventions
 * §4: assert the generated SQL against a fake; real-Postgres execution is the
 * integration suite). The text-search config must match V2__memory.sql's generated
 * `tsv` column (`'simple'`), and the vector path must use pgvector cosine `<=>`.
 */
class PgMemorySqlTest {
    @Test
    fun `fts candidate sql ranks by ts_rank_cd over the fixed simple config`() {
        val sql = PgMemorySql.ftsCandidateSql(categories = null)
        assertTrue(sql.contains("ts_rank_cd(mc.tsv, plainto_tsquery('simple', ?))"), sql)
        assertTrue(sql.contains("mc.tsv @@ plainto_tsquery('simple', ?)"), sql)
        assertTrue(sql.contains("md.scope = ?"), sql)
        assertTrue(sql.contains("ORDER BY rank DESC"), sql)
        assertTrue(sql.trimEnd().endsWith("LIMIT ?"), sql)
        assertFalse(sql.contains("md.category IN"), "no category clause when categories=null")
    }

    @Test
    fun `vec candidate sql orders by pgvector cosine distance with a vector cast`() {
        val sql = PgMemorySql.vecCandidateSql(categories = null)
        assertTrue(sql.contains("ORDER BY mc.embedding <=> ?::vector"), sql)
        assertTrue(sql.contains("mc.embedding IS NOT NULL"), sql)
        assertTrue(sql.contains("md.scope = ?"), sql)
        assertTrue(sql.trimEnd().endsWith("LIMIT ?"), sql)
    }

    @Test
    fun `category filter is inlined from the closed enum set when present`() {
        val cats = setOf(MemoryCategory.Fact, MemoryCategory.Preference)
        val fts = PgMemorySql.ftsCandidateSql(cats)
        assertTrue(fts.contains("md.category IN ('Fact', 'Preference')"), fts)
        val vec = PgMemorySql.vecCandidateSql(cats)
        assertTrue(vec.contains("md.category IN ('Fact', 'Preference')"), vec)
    }

    @Test
    fun `vector literal uses pgvector bracket format`() {
        assertEquals("[1.0,2.5,-3.0]", PgMemorySql.vectorLiteral(floatArrayOf(1.0f, 2.5f, -3.0f)))
    }
}
