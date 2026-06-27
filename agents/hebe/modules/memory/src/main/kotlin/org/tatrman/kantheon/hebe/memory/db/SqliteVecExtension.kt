package org.tatrman.kantheon.hebe.memory.db

import java.io.File
import java.sql.Connection

object SqliteVecExtension {
    /**
     * Returns the path to the native vec0 extension for the current platform, extracting it from
     * the classpath to a temp file if needed (e.g. when running from a fat JAR).
     */
    fun resolveLibPath(): File {
        val (os, arch) = detectPlatform()
        val libName = libName(os)
        val resource = "/native/sqlite-vec/$os-$arch/$libName"

        val url =
            SqliteVecExtension::class.java.getResource(resource)
                ?: error("sqlite-vec not found for $os-$arch at $resource")

        return if (url.protocol == "file") {
            File(url.toURI())
        } else {
            val tmpDir =
                java.nio.file.Files
                    .createTempDirectory("sqlite-vec")
            val file = tmpDir.resolve(libName)
            java.nio.file.Files
                .copy(url.openStream(), file)
            file.toFile().setExecutable(true)
            file.toFile()
        }
    }

    /** Loads the vec0 extension on an already-extension-enabled connection. */
    fun loadOnConnection(connection: Connection) {
        val path = resolveLibPath().absolutePath
        connection.createStatement().use { st ->
            st.execute("SELECT load_extension('$path')")
        }
    }

    private fun detectPlatform(): Pair<String, String> {
        val os =
            when {
                System.getProperty("os.name").lowercase().contains("mac") -> "darwin"
                System.getProperty("os.name").lowercase().contains("linux") -> "linux"
                else -> error("Unsupported OS: ${System.getProperty("os.name")}")
            }
        val arch =
            when (System.getProperty("os.arch")) {
                "aarch64", "arm64" -> "aarch64"
                "x86_64", "amd64" -> "x86_64"
                else -> error("Unsupported arch: ${System.getProperty("os.arch")}")
            }
        return os to arch
    }

    private fun libName(os: String): String =
        when (os) {
            "darwin" -> "vec0.dylib"
            "linux" -> "vec0.so"
            else -> error("Unsupported OS: $os")
        }
}
