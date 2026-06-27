package org.tatrman.kantheon.report.template

import org.tatrman.kantheon.report.v1.ParamDef
import org.tatrman.kantheon.report.v1.ParamKind
import org.tatrman.kantheon.report.v1.ReportFormat
import org.tatrman.kantheon.report.v1.ReportTemplate

/** The three v1 report templates (contracts §9.3). `storage_ref` is the classpath path the
 *  resolver reads bytes from (`templates/{id-with-dots/colons→slashes}.{ext}`). */
object TemplateRegistry {
    val PORTFOLIO_STATEMENT: ReportTemplate =
        template("portfolio-statement:v1", "Portfolio Statement", ReportFormat.REPORT_XLSX)
    val PERFORMANCE_REPORT: ReportTemplate =
        template("performance-report:v1", "Performance Report", ReportFormat.REPORT_XLSX)
    val TRANSACTION_LEDGER: ReportTemplate =
        template("transaction-ledger:v1", "Transaction Ledger", ReportFormat.REPORT_XLSX)

    val ALL: List<ReportTemplate> = listOf(PORTFOLIO_STATEMENT, PERFORMANCE_REPORT, TRANSACTION_LEDGER)

    fun byId(id: String): ReportTemplate? = ALL.firstOrNull { it.templateId == id }

    private fun template(
        id: String,
        name: String,
        format: ReportFormat,
    ): ReportTemplate =
        ReportTemplate
            .newBuilder()
            .setTemplateId(id)
            .setDisplayName(name)
            .setFormat(format)
            .setVersion("1.0.0")
            .setActive(true)
            .setStorageRef("templates/${id.replace(':', '/').replace('.', '/')}.xlsx")
            .addParams(param("portfolio_id", ParamKind.PARAM_PORTFOLIO_ID, required = true))
            .addParams(param("as_of", ParamKind.PARAM_DATE, required = false))
            .addParams(param("period", ParamKind.PARAM_PERIOD, required = false, default = "ytd"))
            .build()

    private fun param(
        name: String,
        kind: ParamKind,
        required: Boolean,
        default: String = "",
    ): ParamDef =
        ParamDef
            .newBuilder()
            .setName(name)
            .setKind(kind)
            .setRequired(required)
            .setDefaultValue(default)
            .build()
}

/** Reads a template's bytes. v1 reads the repo-bundled classpath resources; a v1.x
 *  `S3Resolver` swaps in behind this interface without an API change (architecture §10.1). */
interface TemplateResolver {
    fun resolve(templateId: String): ReportTemplate?

    fun bytes(template: ReportTemplate): ByteArray?
}

/** Classpath-bundled resolver — reads `src/main/resources/templates/…`. */
class RepoBundledResolver(
    private val loader: ClassLoader = RepoBundledResolver::class.java.classLoader,
) : TemplateResolver {
    override fun resolve(templateId: String): ReportTemplate? = TemplateRegistry.byId(templateId)

    override fun bytes(template: ReportTemplate): ByteArray? =
        loader.getResourceAsStream(template.storageRef)?.use { it.readBytes() }
}
