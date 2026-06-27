import { ref, readonly } from 'vue'
import { authHeaders } from '@/services/authHeaders'
import type { ReadyResponse } from '@/types/agent-responses'

export type ConnectionStatus = 'ok' | 'degraded' | 'disconnected'

const POLL_INTERVAL_MS = 30_000
const MAX_BACKOFF_MS = 60_000
const DISCONNECT_THRESHOLD_MS = 60_000

// Module-level singleton state — one polling loop for the whole app.
const _status = ref<ConnectionStatus>('disconnected')
// Stage 07 — `_lastInstanceId` retained as a `null` placeholder so call
// sites that destructure `lastInstanceId` from the composable still type.
// Restart detection is deferred (see _poll comment).
const _lastInstanceId = ref<string | null>(null)
const _lastSuccessAt = ref<Date | null>(null)
const _lastError = ref<string | null>(null)

let _pollTimer: ReturnType<typeof setTimeout> | null = null
let _backoff = POLL_INTERVAL_MS
let _currentBaseUrl = ''
let _currentUserId: string | null = null
let _started = false

const _poll = async () => {
    try {
        // Stage 2.2 — poll the BFF readiness probe (GET /ready, not under /v1).
        // It returns {status:"UP"} (200) or {status:"NOT_READY"} (503). The
        // bearer is harmless here (the probe is unauthenticated) but kept so the
        // poll behaves identically once the probe moves behind the JWT edge.
        const url = new URL(`${_currentBaseUrl}/ready`)
        const res = await fetch(url.toString(), { headers: await authHeaders() })
        if (!res.ok) {
            throw new Error(`HTTP ${res.status}`)
        }
        const data: ReadyResponse = await res.json()

        const newStatus: ConnectionStatus =
            data.status === 'UP' ? 'ok' : 'degraded'

        _status.value = newStatus
        _lastSuccessAt.value = new Date()
        _lastError.value = null
        _backoff = POLL_INTERVAL_MS

        if (_pollTimer) clearTimeout(_pollTimer)
        _pollTimer = setTimeout(_poll, _backoff)
    } catch (err) {
        _lastError.value = err instanceof Error ? err.message : String(err)
        _backoff = Math.min(_backoff * 2, MAX_BACKOFF_MS)

        const timeSinceLastSuccess = _lastSuccessAt.value
            ? Date.now() - _lastSuccessAt.value.getTime()
            : Infinity

        if (timeSinceLastSuccess > DISCONNECT_THRESHOLD_MS) {
            _status.value = 'disconnected'
        } else {
            _status.value = 'degraded'
        }

        if (_pollTimer) clearTimeout(_pollTimer)
        _pollTimer = setTimeout(_poll, _backoff)
    }
}

export function useConnectionStatus() {
    const start = (baseUrl: string, userId: string | null) => {
        if (_started) return
        _currentBaseUrl = baseUrl
        _currentUserId = userId
        _started = true
        _poll()
    }

    const stop = () => {
        if (_pollTimer) {
            clearTimeout(_pollTimer)
            _pollTimer = null
        }
        _started = false
    }

    return {
        status: readonly(_status),
        lastInstanceId: readonly(_lastInstanceId),
        lastSuccessAt: readonly(_lastSuccessAt),
        lastError: readonly(_lastError),
        start,
        stop,
    }
}
