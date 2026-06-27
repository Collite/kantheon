package org.tatrman.kantheon.hebe.api.workspace

@JvmInline
value class WorkspacePath(
    val value: String,
) {
    init {
        require(!value.contains("..")) { "Path cannot contain '..': $value" }
        require(!value.startsWith("/")) { "Path cannot be absolute: $value" }
    }

    fun resolve(child: String): WorkspacePath {
        require(!child.startsWith("/")) { "Child cannot be absolute: $child" }
        require(!child.contains("..")) { "Child cannot contain '..': $child" }
        return WorkspacePath("$value/$child")
    }
}
