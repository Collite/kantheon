package org.tatrman.kantheon.hebe.tools.dispatch

import org.tatrman.kantheon.hebe.api.ToolResult

sealed interface DispatchOutcome {
    data class Result(
        val result: ToolResult,
    ) : DispatchOutcome
}
