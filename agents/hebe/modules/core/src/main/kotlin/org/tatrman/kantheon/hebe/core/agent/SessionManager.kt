package org.tatrman.kantheon.hebe.core.agent

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex

class SessionManager {
    private val sessions = ConcurrentHashMap<String, Mutex>()

    fun getOrCreate(sessionId: String): Mutex = sessions.computeIfAbsent(sessionId) { Mutex() }

    fun closeSession(sessionId: String) {
        sessions.remove(sessionId)
    }
}
