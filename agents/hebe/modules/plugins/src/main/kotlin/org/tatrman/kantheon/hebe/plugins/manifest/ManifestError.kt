@file:Suppress("NewLineAtEndOfFile")

package org.tatrman.kantheon.hebe.plugins.manifest

data class ManifestError(
    val message: String,
    val source: String? = null,
    val line: Int? = null,
    val column: Int? = null,
) {
    fun withSource(source: String): ManifestError = copy(source = source)

    fun withPosition(
        line: Int,
        column: Int,
    ): ManifestError = copy(line = line, column = column)

    override fun toString(): String {
        val location =
            when {
                line != null && column != null -> "$source:$line:$column"
                line != null -> "$source:$line"
                source != null -> source
                else -> "<unknown>"
            }
        return "$location: $message"
    }
}

sealed class ManifestResult<out T> {
    data class Ok<T>(
        val value: T,
    ) : ManifestResult<T>()

    data class Error(
        val errors: List<ManifestError>,
    ) : ManifestResult<Nothing>()

    fun isOk(): Boolean = this is Ok

    fun isError(): Boolean = this is Error

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorsOrNull(): List<ManifestError>? = (this as? Error)?.errors
}
