package org.tatrman.kantheon.sysifos.bff.api

import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.route
import org.tatrman.kantheon.sysifos.bff.dictionaries.DictionaryService

/**
 * Dictionary surface (contracts §3.7). Static-ish reference data served from the
 * BFF cache (TTL 10 min). Read-only; no auth gate beyond the BFF being reachable
 * (these carry no tenant data).
 */
fun Route.dictionaryRoutes(dictionaries: DictionaryService) {
    route("/dictionaries") {
        get("/brokers") { call.respond(dictionaries.brokers.get()) }
        get("/currencies") { call.respond(dictionaries.currencies.get()) }
        get("/transaction-kinds") { call.respond(dictionaries.transactionKinds.get()) }
        get("/asset-kinds") { call.respond(dictionaries.assetKinds.get()) }
    }
}
