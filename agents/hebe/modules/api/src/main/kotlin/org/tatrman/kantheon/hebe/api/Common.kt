package org.tatrman.kantheon.hebe.api

import kotlinx.serialization.Serializable

@Serializable
enum class RiskLevel {
    Low,
    Medium,
    High,
}

@Serializable
enum class AutonomyLevel {
    ReadOnly,
    Supervised,
    Full,
    YOLO,
}

@Serializable
enum class PathScope {
    WorkspaceOnly,
    ConfiguredRoots,
    Anywhere,
}

interface SecretLookup {
    fun secret(name: String): String?
}
