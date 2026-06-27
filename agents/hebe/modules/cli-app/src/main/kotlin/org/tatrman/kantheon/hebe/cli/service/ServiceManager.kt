@file:Suppress("TooGenericExceptionCaught", "MagicNumber")

package org.tatrman.kantheon.hebe.cli.service

import java.nio.file.Files
import java.nio.file.Path
import org.slf4j.LoggerFactory

sealed interface ServiceStatus {
    data object Running : ServiceStatus

    data object Stopped : ServiceStatus

    data object NotInstalled : ServiceStatus
}

interface PlatformService {
    fun install(
        jarPath: String,
        javaPath: String,
    ): Result<Unit>

    fun uninstall(): Result<Unit>

    fun start(): Result<Unit>

    fun stop(): Result<Unit>

    fun status(): ServiceStatus
}

fun platformService(dataDir: Path): PlatformService {
    val os = System.getProperty("os.name").lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") -> MacOsLaunchdService(dataDir)
        os.contains("linux") -> LinuxSystemdService(dataDir)
        else -> UnsupportedPlatformService(os)
    }
}

private class UnsupportedPlatformService(
    private val os: String,
) : PlatformService {
    /**
     * Windows does not support launchd or systemd-based service management.
     * Use a third-party service manager like NSSM or Windows Service Wrapper instead.
     */
    override fun install(
        jarPath: String,
        javaPath: String,
    ) = Result.failure<Unit>(
        UnsupportedOperationException(
            "Service management not supported on $os (Windows). Use a third-party service manager like NSSM.",
        ),
    )

    override fun uninstall() =
        Result.failure<Unit>(
            UnsupportedOperationException(
                "Service management not supported on $os (Windows). Use a third-party service manager like NSSM.",
            ),
        )

    override fun start() =
        Result.failure<Unit>(
            UnsupportedOperationException(
                "Service management not supported on $os (Windows). Use a third-party service manager like NSSM.",
            ),
        )

    override fun stop() =
        Result.failure<Unit>(
            UnsupportedOperationException(
                "Service management not supported on $os (Windows). Use a third-party service manager like NSSM.",
            ),
        )

    override fun status() = ServiceStatus.NotInstalled
}

class LinuxSystemdService(
    private val dataDir: Path,
) : PlatformService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val unitDir = Path.of(System.getProperty("user.home"), ".config", "systemd", "user")
    private val unitFile = unitDir.resolve("hebe.service")

    private fun unitContent(
        jarPath: String,
        javaPath: String,
    ): String =
        """
        [Unit]
        Description=hebe agent
        After=network.target

        [Service]
        Type=simple
        ExecStart=$javaPath -jar $jarPath run
        Environment=HOME=%h
        Restart=on-failure
        RestartSec=5

        [Install]
        WantedBy=default.target
        """.trimIndent()

    override fun install(
        jarPath: String,
        javaPath: String,
    ): Result<Unit> =
        runCatching {
            Files.createDirectories(unitDir)
            Files.writeString(unitFile, unitContent(jarPath, javaPath))
            exec("systemctl", "--user", "daemon-reload")
            exec("systemctl", "--user", "enable", "hebe.service")
            log.info("systemd unit installed at {}", unitFile)
        }

    override fun uninstall(): Result<Unit> =
        runCatching {
            exec("systemctl", "--user", "disable", "--now", "hebe.service")
            Files.deleteIfExists(unitFile)
            exec("systemctl", "--user", "daemon-reload")
        }

    override fun start(): Result<Unit> = runCatching { exec("systemctl", "--user", "start", "hebe.service") }

    override fun stop(): Result<Unit> = runCatching { exec("systemctl", "--user", "stop", "hebe.service") }

    override fun status(): ServiceStatus {
        if (!Files.exists(unitFile)) return ServiceStatus.NotInstalled
        return try {
            val proc = ProcessBuilder("systemctl", "--user", "is-active", "hebe.service").start()
            val out =
                proc.inputStream
                    .bufferedReader()
                    .readText()
                    .trim()
            proc.waitFor()
            if (out == "active") ServiceStatus.Running else ServiceStatus.Stopped
        } catch (_: Exception) {
            ServiceStatus.NotInstalled
        }
    }

    private fun exec(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd).inheritIO().start()
        val code = proc.waitFor()
        if (code != 0) error("Command failed (exit $code): ${cmd.joinToString(" ")}")
    }
}

class MacOsLaunchdService(
    private val dataDir: Path,
) : PlatformService {
    private val log = LoggerFactory.getLogger(javaClass)
    private val agentsDir = Path.of(System.getProperty("user.home"), "Library", "LaunchAgents")
    private val plistFile = agentsDir.resolve("org.tatrman.kantheon.hebe.agent.plist")

    private fun plistContent(
        jarPath: String,
        javaPath: String,
    ): String =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict>
            <key>Label</key>
            <string>org.tatrman.kantheon.hebe.agent</string>
            <key>ProgramArguments</key>
            <array>
                <string>$javaPath</string>
                <string>-jar</string>
                <string>$jarPath</string>
                <string>run</string>
            </array>
            <key>RunAtLoad</key>
            <true/>
            <key>KeepAlive</key>
            <true/>
            <key>StandardOutPath</key>
            <string>${dataDir.resolve("hebe.log")}</string>
            <key>StandardErrorPath</key>
            <string>${dataDir.resolve("hebe.log")}</string>
        </dict>
        </plist>
        """.trimIndent()

    override fun install(
        jarPath: String,
        javaPath: String,
    ): Result<Unit> =
        runCatching {
            Files.createDirectories(agentsDir)
            Files.writeString(plistFile, plistContent(jarPath, javaPath))
            exec("launchctl", "load", plistFile.toString())
            log.info("launchd plist installed at {}", plistFile)
        }

    override fun uninstall(): Result<Unit> =
        runCatching {
            if (Files.exists(plistFile)) {
                exec("launchctl", "unload", plistFile.toString())
                Files.deleteIfExists(plistFile)
            }
        }

    override fun start(): Result<Unit> = runCatching { exec("launchctl", "start", "org.tatrman.kantheon.hebe.agent") }

    override fun stop(): Result<Unit> = runCatching { exec("launchctl", "stop", "org.tatrman.kantheon.hebe.agent") }

    override fun status(): ServiceStatus {
        if (!Files.exists(plistFile)) return ServiceStatus.NotInstalled
        return try {
            val proc = ProcessBuilder("launchctl", "list", "org.tatrman.kantheon.hebe.agent").start()
            val out = proc.inputStream.bufferedReader().readText()
            proc.waitFor()
            if ("org.tatrman.kantheon.hebe.agent" in out && "\"PID\"" in out) ServiceStatus.Running else ServiceStatus.Stopped
        } catch (_: Exception) {
            ServiceStatus.NotInstalled
        }
    }

    private fun exec(vararg cmd: String) {
        val proc = ProcessBuilder(*cmd).inheritIO().start()
        val code = proc.waitFor()
        if (code != 0) error("Command failed (exit $code): ${cmd.joinToString(" ")}")
    }
}
