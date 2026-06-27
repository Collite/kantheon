package shared.translator.detect

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import shared.translator.codec.sql.ParseResult
import shared.translator.codec.sql.SqlParser

class TableIdentifierExtractorSpec :
    StringSpec({

        "single table from SELECT" {
            val sql = "SELECT id, name FROM QSKUPZBOZI_DF WHERE nazev LIKE 'O%'"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "multiple tables from JOIN" {
            val sql = "SELECT * FROM QSKUPZBOZI_DF t1 JOIN QZBOZI_DF t2 ON t1.id = t2.skupzbozi_id"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df", "qzbozi_df")
        }

        "tables from comma/implicit join" {
            val sql = "SELECT * FROM QSKUPZBOZI_DF, QZBOZI_DF WHERE QSKUPZBOZI_DF.id = QZBOZI_DF.skupzbozi_id"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df", "qzbozi_df")
        }

        "aliased table with AS" {
            val sql = "SELECT t.id, t.nazev FROM QSKUPZBOZI_DF AS t WHERE t.nazev LIKE 'O%'"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "aliased table without AS" {
            val sql = "SELECT t.id, t.nazev FROM QSKUPZBOZI_DF t WHERE t.nazev LIKE 'O%'"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "subquery in FROM" {
            val sql = "SELECT * FROM (SELECT id, nazev FROM QSKUPZBOZI_DF) AS subq"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "CTE with WITH" {
            val sql = "WITH c AS (SELECT id, nazev FROM QSKUPZBOZI_DF) SELECT * FROM c"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "CTE excluded from FROM" {
            val sql = "WITH c AS (SELECT id FROM QSKUPZBOZI_DF) SELECT * FROM c"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "UNION of two selects" {
            val sql = "SELECT id FROM QSKUPZBOZI_DF UNION SELECT id FROM QZBOZI_DF"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df", "qzbozi_df")
        }

        "ORDER BY wrapped query" {
            val sql = "SELECT * FROM QSKUPZBOZI_DF ORDER BY nazev"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "qualified table name uses last part" {
            val sql = "SELECT id FROM dbo.QSKUPZBOZI_DF"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df")
        }

        "qualified join keeps both last parts" {
            val sql = "SELECT * FROM dbo.QSKUPZBOZI_DF a JOIN dbo.QZBOZI_DF b ON a.id = b.skup_id"
            val parseResult = SqlParser.parseQuery(sql)
            val success = parseResult as ParseResult.Success
            val tables = TableIdentifierExtractor.fromQuery(success.sqlNode)
            tables shouldContainExactlyInAnyOrder listOf("qskupzbozi_df", "qzbozi_df")
        }
    })
