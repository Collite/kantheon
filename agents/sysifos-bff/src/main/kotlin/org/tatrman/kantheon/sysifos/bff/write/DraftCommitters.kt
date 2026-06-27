package org.tatrman.kantheon.sysifos.bff.write

import com.google.protobuf.util.JsonFormat
import io.ktor.http.HttpMethod
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.tatrman.kantheon.bffbase.auth.CallerIdentity
import org.tatrman.kantheon.midas.v1.Client
import org.tatrman.kantheon.midas.v1.ClientResponse
import org.tatrman.kantheon.midas.v1.CreateClientRequest
import org.tatrman.kantheon.sysifos.bff.midas.MidasCoreClient
import org.tatrman.kantheon.sysifos.bff.midas.MidasResponse
import org.tatrman.kantheon.sysifos.v1.ClientForm
import org.tatrman.kantheon.sysifos.v1.Draft
import org.tatrman.kantheon.sysifos.v1.DraftKind
import org.tatrman.kantheon.sysifos.v1.FieldValidationError

private val jsonParser: JsonFormat.Parser = JsonFormat.parser().ignoringUnknownFields()
private val jsonPrinter: JsonFormat.Printer = JsonFormat.printer().omittingInsignificantWhitespace()
private val errorJson = Json { ignoreUnknownKeys = true }

/**
 * Turn a Midas-core error response into a [CommitOutcome.Rejected], carrying the
 * structured `{ error: { code, message, field } }` envelope (Midas contracts §12)
 * through as a typed [FieldValidationError] so the FE can show the offending field
 * and reason — instead of a generic opaque "VALIDATION_FAILED". The error code (when
 * present) becomes the rejection reason so distinct failure classes (409 conflict,
 * 403 RLS, 400 validation) are not flattened. `row_index = -1` marks a single-record
 * (non-grid) error.
 */
fun midasRejection(resp: MidasResponse): CommitOutcome.Rejected {
    val error =
        runCatching {
            errorJson.parseToJsonElement(resp.body).jsonObject["error"]?.jsonObject
        }.getOrNull()
    val code = error?.get("code")?.jsonPrimitive?.contentOrNull
    val message = error?.get("message")?.jsonPrimitive?.contentOrNull
    val field = error?.get("field")?.jsonPrimitive?.contentOrNull
    val errors =
        if (code != null || message != null || field != null) {
            listOf(
                FieldValidationError
                    .newBuilder()
                    .also { b ->
                        field?.let { b.field = it }
                        code?.let { b.code = it }
                        message?.let { b.message = it }
                    }.setRowIndex(-1)
                    .build(),
            )
        } else {
            emptyList()
        }
    return CommitOutcome.Rejected(code ?: "VALIDATION_FAILED", errors)
}

/**
 * `DRAFT_CLIENT` committer (contracts §3.2). Maps `Draft.payload_json` → `ClientForm`
 * → Midas-core `CreateClientRequest` and `POST /api/v1/clients`. A 2xx yields the
 * new `client_id` as the artifact ref; a 4xx rejects (the tenant is the JWT's, set
 * by Midas from the forwarded header — never from the form).
 */
class ClientDraftCommitter(
    private val midas: MidasCoreClient,
) : DraftCommitter {
    override suspend fun commit(
        draft: Draft,
        caller: CallerIdentity,
        sink: DraftEventSink,
    ): CommitOutcome {
        val form = ClientForm.newBuilder().also { jsonParser.merge(draft.payloadJson, it) }.build()
        val request =
            CreateClientRequest
                .newBuilder()
                .setClient(
                    Client
                        .newBuilder()
                        .setName(form.name)
                        .setContactEmail(form.contactEmail)
                        .setContactPhone(form.contactPhone),
                ).build()

        val resp = midas.forward(HttpMethod.Post, "/api/v1/clients", caller, jsonPrinter.print(request))
        if (!resp.isSuccess) {
            return midasRejection(resp)
        }
        val clientId =
            runCatching {
                ClientResponse
                    .newBuilder()
                    .also { jsonParser.merge(resp.body, it) }
                    .build()
                    .client.clientId
            }.getOrDefault("")
        return CommitOutcome.Committed(artifactRef = clientId)
    }
}

/**
 * The committer registry — `DRAFT_CLIENT` (1.3), `DRAFT_TRANSACTION_BATCH` (2.3),
 * `DRAFT_LOADER_RUN_COMMIT` (2.5). The import commit goes to the [loader] service;
 * everything else to [midas].
 */
fun defaultCommitters(
    midas: MidasCoreClient,
    loader: MidasCoreClient,
): Map<DraftKind, DraftCommitter> =
    mapOf(
        DraftKind.DRAFT_CLIENT to ClientDraftCommitter(midas),
        DraftKind.DRAFT_TRANSACTION_BATCH to TransactionBatchDraftCommitter(midas),
        DraftKind.DRAFT_LOADER_RUN_COMMIT to LoaderRunCommitDraftCommitter(loader),
    )
