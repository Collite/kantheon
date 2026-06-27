package org.tatrman.kantheon.report

import com.google.protobuf.Timestamp
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.report.engine.DataFetcher
import org.tatrman.kantheon.report.engine.XlsxRenderer
import org.tatrman.kantheon.report.store.ArtifactStore
import org.tatrman.kantheon.report.template.ParamValidator
import org.tatrman.kantheon.report.template.TemplateResolver
import org.tatrman.kantheon.report.template.ValidationResult
import org.tatrman.kantheon.report.v1.OutputFormat
import org.tatrman.kantheon.report.v1.RenderReportRequest
import org.tatrman.kantheon.report.v1.RenderReportResponse
import java.time.Duration
import java.time.Instant

/** A render failed before producing an artifact (unknown template / invalid args / unsupported format). */
class RenderException(
    val code: String,
    message: String,
) : RuntimeException(message)

/**
 * Orchestrates a synchronous render (Stage 3.4 T6): resolve the template → validate args →
 * fetch the data → render → store the artifact → return the download descriptor. v1 renders
 * the native **XLSX** path; PPTX (POI slides) and PDF/HTML (Playwright headless Chromium) are
 * integration-deferred and rejected with a clear `unsupported_format` until their engines wire in.
 */
class RenderService(
    private val resolver: TemplateResolver,
    private val data: DataFetcher,
    private val artifacts: ArtifactStore,
    private val ttl: Duration = Duration.ofDays(7),
    private val clock: () -> Instant = Instant::now,
) {
    fun render(request: RenderReportRequest): RenderReportResponse {
        val template =
            resolver.resolve(request.templateId)
                ?: throw RenderException("unknown_template", "no template '${request.templateId}'")

        when (val v = ParamValidator.validate(request.argsJson, template.paramsList)) {
            is ValidationResult.Invalid -> throw RenderException("invalid_args", v.errors.joinToString("; "))
            is ValidationResult.Ok -> {
                if (request.outputFormat != OutputFormat.OUTPUT_XLSX) {
                    throw RenderException(
                        "unsupported_format",
                        "${request.outputFormat.name} is integration-deferred; v1 renders OUTPUT_XLSX",
                    )
                }
                val bytes =
                    resolver.bytes(template)
                        ?: throw RenderException(
                            "template_missing_bytes",
                            "template '${template.templateId}' has no file",
                        )
                val rendered = XlsxRenderer.render(bytes, data.fetch(template.templateId, v.args))
                val stored = artifacts.write(rendered, "xlsx")
                val now = clock()
                val expires = now.plus(ttl)
                return RenderReportResponse
                    .newBuilder()
                    .setArtifactId(stored.artifactId)
                    .setArtifactUrl("/artifacts/${stored.artifactId}")
                    .setMimeType(XLSX_MIME)
                    .setSizeBytes(stored.sizeBytes)
                    .setGeneratedAt(now.toProtoTimestamp())
                    .setExpiresAt(expires.toProtoTimestamp())
                    .addMessages(info("render_ok", "rendered ${template.displayName} as XLSX"))
                    .build()
            }
        }
    }

    private fun info(
        code: String,
        message: String,
    ): ResponseMessage =
        ResponseMessage
            .newBuilder()
            .setSeverity(Severity.INFO)
            .setCode(code)
            .setHumanMessage(message)
            .build()

    private fun Instant.toProtoTimestamp(): Timestamp =
        Timestamp
            .newBuilder()
            .setSeconds(epochSecond)
            .setNanos(nano)
            .build()

    companion object {
        const val XLSX_MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    }
}
