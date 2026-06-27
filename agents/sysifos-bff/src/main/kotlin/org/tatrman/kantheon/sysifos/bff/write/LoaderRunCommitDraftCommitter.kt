package org.tatrman.kantheon.sysifos.bff.write

import com.google.protobuf.util.JsonFormat
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.midas.v1.BatchInsertTransactionsResponse
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient
import org.tatrman.kantheon.sysifos.v1.Draft

private val loaderJson = Json { ignoreUnknownKeys = true }
private val loaderParser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()

/**
 * `DRAFT_LOADER_RUN_COMMIT` committer (S5 import; contracts §3.2, §3.5). The draft
 * `payload_json` is an ad-hoc `{ loaderRunId, skipExisting }` (no proto form — the
 * commit is a lifecycle trigger, not a record). Maps to the Excel loader's
 * `POST /api/v1/runs/{id}/commit { skip_existing, confirm: true }`; the loader
 * batches to Midas-core (deriving cash legs) and returns a
 * `BatchInsertTransactionsResponse`, whose counts become `DraftCommitted`.
 */
class LoaderRunCommitDraftCommitter(
    private val loader: MidasCoreClient,
) : DraftCommitter {
    override suspend fun commit(
        draft: Draft,
        caller: CallerIdentity,
        sink: DraftEventSink,
    ): CommitOutcome {
        val payload: JsonObject =
            runCatching { loaderJson.parseToJsonElement(draft.payloadJson).jsonObject }
                .getOrElse { return CommitOutcome.Rejected("VALIDATION_FAILED", emptyList()) }
        val runId = payload["loaderRunId"]?.jsonPrimitive?.content
        if (runId.isNullOrEmpty()) return CommitOutcome.Rejected("VALIDATION_FAILED", emptyList())
        val skipExisting = runCatching { payload["skipExisting"]!!.jsonPrimitive.boolean }.getOrDefault(true)

        val body = """{"skip_existing":$skipExisting,"confirm":true}"""
        val resp = loader.forward(HttpMethod.Post, "/api/v1/runs/$runId/commit", caller, body)
        if (!resp.isSuccess) return midasRejection(resp)

        val parsed =
            runCatching {
                BatchInsertTransactionsResponse
                    .newBuilder()
                    .also { loaderParser.merge(resp.body, it) }
                    .build()
            }.getOrNull()

        return CommitOutcome.Committed(
            artifactRef = runId,
            committedCount = parsed?.insertedCount ?: 0,
            skippedCount = parsed?.skippedCount ?: 0,
        )
    }
}
