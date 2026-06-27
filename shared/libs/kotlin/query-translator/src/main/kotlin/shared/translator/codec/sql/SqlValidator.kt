package shared.translator.codec.sql

import org.apache.calcite.rel.RelNode
import org.apache.calcite.sql.parser.SqlParseException
import org.apache.calcite.sql.validate.SqlValidatorException
import org.apache.calcite.tools.Planner
import org.apache.calcite.tools.RelConversionException
import org.apache.calcite.tools.ValidationException

/**
 * Parse + validate + convert SQL text to a logical [RelNode].
 *
 * Calcite's [Planner] enforces a strict state machine
 * (`RESET → PARSED → VALIDATED → CONVERTED`); the only way to call
 * `validate(SqlNode)` is on a planner that has already executed `parse(text)`,
 * so this wrapper drives all three stages through the same planner instance.
 *
 * Calcite engagement rule #2 reminder: the [Planner] passed in MUST be fresh
 * (one per query stage). Reuse will throw on the second call to any state
 * transition.
 *
 * The standalone [SqlParser.parseQuery] remains for inspection-only use cases
 * (UI parse-error highlighting, lint, dry-run shape detection) where no
 * planner is in scope.
 */
object SqlValidator {
    fun validateAndConvert(
        planner: Planner,
        sqlText: String,
    ): ValidateResult =
        try {
            val parsed = planner.parse(sqlText)
            val validated = planner.validate(parsed)
            val rel = planner.rel(validated).rel
            ValidateResult.Success(rel)
        } catch (ex: SqlParseException) {
            ValidateResult.Failure(toError("parse_failed", ex))
        } catch (ex: ValidationException) {
            ValidateResult.Failure(toError("validation_failed", ex))
        } catch (ex: SqlValidatorException) {
            ValidateResult.Failure(toError("validation_failed", ex))
        } catch (ex: RelConversionException) {
            ValidateResult.Failure(toError("rel_conversion_failed", ex))
        } catch (ex: RuntimeException) {
            // Calcite throws plenty of *unchecked* exceptions the four typed catches above miss:
            // CalciteContextException (positioned validation errors), the SqlToRelConverter
            // "while converting <expr>" wrapper, NlsString charset-encode failures, and assorted
            // RESOURCE.* errors. Left uncaught these escaped the gRPC handler as a bare UNKNOWN
            // with no detail. Map them to a structured failure and — crucially — surface the
            // nested cause via [toError], since the outer message is often just "while converting …"
            // while the real reason ("Failed to encode … in character set …") lives in the cause.
            ValidateResult.Failure(toError("rel_conversion_failed", ex))
        }

    /**
     * Render a single-line diagnostic by walking the exception's cause chain outermost→root and
     * joining the distinct messages. Calcite nests the real reason as a cause — e.g. outer
     * `"while converting \`u\`.\`x\` = 'Poštovné'"` → cause `"Failed to encode 'Poštovné' in
     * character set 'ISO-8859-1'"` — so reporting only the outer message hides it. Duplicate
     * messages (wrappers that copy their cause's text) are collapsed.
     */
    private fun toError(
        code: String,
        ex: Throwable,
    ): SqlValidationError = SqlValidationError(code = code, message = describeCauseChain(ex))

    private fun describeCauseChain(ex: Throwable): String {
        val messages = LinkedHashSet<String>()
        var current: Throwable? = ex
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { messages.add(it) }
            val next = current.cause
            if (next === current) break // defend against self-referential cause chains
            current = next
        }
        return messages.joinToString(": ").ifEmpty { ex.javaClass.simpleName }
    }
}

sealed interface ValidateResult {
    data class Success(
        val rel: RelNode,
    ) : ValidateResult

    data class Failure(
        val error: SqlValidationError,
    ) : ValidateResult
}

data class SqlValidationError(
    val code: String,
    val message: String,
)
