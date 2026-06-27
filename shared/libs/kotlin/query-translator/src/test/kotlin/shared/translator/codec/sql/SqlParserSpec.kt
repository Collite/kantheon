package shared.translator.codec.sql

import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf

class SqlParserSpec :
    StringSpec({

        "parses a basic SELECT" {
            val r = SqlParser.parseQuery("SELECT id FROM customers")
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "parses SELECT with WHERE and ORDER BY" {
            val r = SqlParser.parseQuery("SELECT name FROM customers WHERE id > 5 ORDER BY name DESC")
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "parses JOIN" {
            val r =
                SqlParser.parseQuery(
                    "SELECT c.name, o.id FROM customers c JOIN orders o ON o.customer_id = c.id",
                )
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "case-insensitive identifiers (Lex.MYSQL_ANSI)" {
            val r = SqlParser.parseQuery("SELECT NAME FROM Customers")
            r.shouldBeInstanceOf<ParseResult.Success>()
        }

        "rejects malformed SQL with structured error" {
            val r = SqlParser.parseQuery("SELECT FROM WHERE")
            r.shouldBeInstanceOf<ParseResult.Failure>()
            val err = (r as ParseResult.Failure).error
            err.code shouldBe "parse_failed"
            err.line shouldBeGreaterThan 0
        }

        "captures position info on parse failure" {
            val r = SqlParser.parseQuery("SELECT * FORM customers")
            r.shouldBeInstanceOf<ParseResult.Failure>()
            val err = (r as ParseResult.Failure).error
            err.line shouldBeGreaterThan 0
            err.column shouldBeGreaterThan 0
            err.message.shouldContain("Encountered")
        }
    })
