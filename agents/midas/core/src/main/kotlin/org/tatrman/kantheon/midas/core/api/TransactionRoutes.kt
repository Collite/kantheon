package org.tatrman.kantheon.midas.core.api

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import org.tatrman.kantheon.common.v1.ResponseMessage
import org.tatrman.kantheon.common.v1.Severity
import org.tatrman.kantheon.midas.core.auth.BearerAuthenticator
import org.tatrman.kantheon.midas.core.repository.TransactionRepository
import org.tatrman.kantheon.midas.v1.BalanceEntryCommitResponse
import org.tatrman.kantheon.midas.v1.BalanceEntryRequest
import org.tatrman.kantheon.midas.v1.BatchInsertTransactionsRequest
import org.tatrman.kantheon.midas.v1.BatchInsertTransactionsResponse
import org.tatrman.kantheon.midas.v1.EditTransactionRequest
import org.tatrman.kantheon.midas.v1.EditTransactionResponse
import org.tatrman.kantheon.midas.v1.InsertTransactionRequest
import org.tatrman.kantheon.midas.v1.ListTransactionsResponse
import org.tatrman.kantheon.midas.v1.PageInfo
import org.tatrman.kantheon.midas.v1.TransactionResponse

/** Transactions REST surface (contracts §2.4 — append-only; edits are reversals). */
fun Route.transactionRoutes(
    repo: TransactionRepository,
    auth: BearerAuthenticator,
) {
    post("/transactions") {
        val caller = call.requireCaller(auth) ?: return@post
        val tenant = call.resolveTenant(caller)
        val req = call.receiveProto(InsertTransactionRequest.newBuilder()).build()
        val legs = repo.insert(tenant, caller.userId, req.transaction)
        val resp = TransactionResponse.newBuilder().setTransaction(legs.security)
        legs.cash?.let {
            resp.addMessages(
                ResponseMessage
                    .newBuilder()
                    .setSeverity(Severity.INFO)
                    .setCode("cash_leg_derived")
                    .setHumanMessage("derived cash leg ${it.transactionId} (correlation ${it.correlationId})"),
            )
        }
        call.respondProto(resp.build(), HttpStatusCode.Created)
    }

    post("/transactions:batch") {
        val caller = call.requireCaller(auth) ?: return@post
        val tenant = call.resolveTenant(caller)
        val req = call.receiveProto(BatchInsertTransactionsRequest.newBuilder()).build()
        val outcome = repo.batchInsert(tenant, caller.userId, req.transactionsList, req.skipExisting)
        call.respondProto(
            BatchInsertTransactionsResponse
                .newBuilder()
                .setInsertedCount(outcome.inserted.size)
                .setSkippedCount(outcome.skipped)
                .setFailedCount(0)
                .build(),
        )
    }

    get("/transactions") {
        val caller = call.requireCaller(auth) ?: return@get
        val tenant = call.resolveTenant(caller)
        val page = call.request.queryParameters["page"]?.toIntOrNull() ?: 0
        val size = call.request.queryParameters["size"]?.toIntOrNull() ?: 50
        val (items, total) =
            repo.list(
                tenant,
                page,
                size,
                call.request.queryParameters["portfolio_id"],
                call.request.queryParameters["asset_id"],
                call.request.queryParameters["kind"],
            )
        call.respondProto(
            ListTransactionsResponse
                .newBuilder()
                .addAllTransactions(items)
                .setPageInfo(
                    PageInfo
                        .newBuilder()
                        .setPage(page)
                        .setSize(size)
                        .setTotal(total),
                ).build(),
        )
    }

    get("/transactions/{id}") {
        val caller = call.requireCaller(auth) ?: return@get
        val tenant = call.resolveTenant(caller)
        val id = pathUuid("id") ?: return@get
        val txn =
            repo.get(tenant, id)
                ?: throw MidasException.notFound(MidasErrorCode.TRANSACTION_NOT_FOUND, "transaction $id not found")
        call.respondProto(TransactionResponse.newBuilder().setTransaction(txn).build())
    }

    patch("/transactions/{id}") {
        val caller = call.requireCaller(auth) ?: return@patch
        val tenant = call.resolveTenant(caller)
        val id = pathUuid("id") ?: return@patch
        val req = call.receiveProto(EditTransactionRequest.newBuilder()).build()
        val result =
            repo.reverseAndReplace(tenant, caller.userId, id, req.newTransaction, req.reason)
                ?: throw MidasException.notFound(MidasErrorCode.TRANSACTION_NOT_FOUND, "transaction $id not found")
        val resp = EditTransactionResponse.newBuilder().setReversal(result.reversal)
        result.replacement?.let { resp.setReplacement(it) }
        call.respondProto(resp.build())
    }

    delete("/transactions/{id}") {
        val caller = call.requireCaller(auth) ?: return@delete
        val tenant = call.resolveTenant(caller)
        val id = pathUuid("id") ?: return@delete
        val reason = call.request.queryParameters["reason"] ?: ""
        val result =
            repo.reverseAndReplace(tenant, caller.userId, id, null, reason)
                ?: throw MidasException.notFound(MidasErrorCode.TRANSACTION_NOT_FOUND, "transaction $id not found")
        call.respondProto(TransactionResponse.newBuilder().setTransaction(result.reversal).build())
    }

    // Balance entry (contracts §2.5).
    post("/balance-entries:preview") {
        val caller = call.requireCaller(auth) ?: return@post
        val tenant = call.resolveTenant(caller)
        val req = call.receiveProto(BalanceEntryRequest.newBuilder()).build()
        call.respondProto(repo.previewBalance(tenant, req))
    }

    post("/balance-entries:commit") {
        val caller = call.requireCaller(auth) ?: return@post
        val tenant = call.resolveTenant(caller)
        val req = call.receiveProto(BalanceEntryRequest.newBuilder()).build()
        val committed = repo.commitBalance(tenant, caller.userId, req)
        call.respondProto(BalanceEntryCommitResponse.newBuilder().setCommittedTransaction(committed).build())
    }
}
