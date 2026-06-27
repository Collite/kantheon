package org.tatrman.kantheon.hebe.security.policy

import java.net.InetAddress
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

class SsrfGuard(
    private val allowLoopbackFor: List<String> = emptyList(),
) {
    private val dnsCache = ConcurrentHashMap<String, DnsEntry>()
    private val dnsCacheTtlMs = 60_000L

    private val blockedRanges =
        listOf(
            BlockedRange("127.0.0.0/8", "Loopback"),
            BlockedRange("::1", "Loopback"),
            BlockedRange("169.254.0.0/16", "Link-local"),
            BlockedRange("fe80::/10", "Link-local"),
            BlockedRange("10.0.0.0/8", "Private"),
            BlockedRange("172.16.0.0/12", "Private"),
            BlockedRange("192.168.0.0/16", "Private"),
            BlockedRange("fc00::/7", "Unique-local"),
            BlockedRange("169.254.169.254", "AWS/Azure metadata"),
            BlockedRange("metadata.google.internal", "GCP metadata"),
        )

    fun isBlocked(url: String): SsrfResult {
        return try {
            val parsed = URL(url)
            val host = parsed.host

            if (host in allowLoopbackFor) {
                return SsrfResult.Allowed
            }

            val addresses = resolveHostnames(host)
            for (addr in addresses) {
                for (range in blockedRanges) {
                    if (range.contains(addr)) {
                        return SsrfResult.Blocked("URL hostname resolved to blocked range: ${range.description}")
                    }
                }
            }

            SsrfResult.Allowed
        } catch (e: Exception) {
            SsrfResult.Invalid("Failed to parse URL: ${e.message}")
        }
    }

    private fun resolveHostnames(host: String): List<String> {
        if (host in allowLoopbackFor) {
            return listOf("127.0.0.1", "::1")
        }

        val cached = dnsCache[host]
        if (cached != null && System.currentTimeMillis() - cached.timestamp < dnsCacheTtlMs) {
            return cached.addresses
        }

        return try {
            val addresses = InetAddress.getAllByName(host)
            val addrs = addresses.mapNotNull { it.hostAddress }
            if (addrs.isNotEmpty()) {
                dnsCache[host] = DnsEntry(System.currentTimeMillis(), addrs)
            }
            addrs
        } catch (e: Exception) {
            emptyList()
        }
    }

    sealed class SsrfResult {
        data object Allowed : SsrfResult()

        data class Blocked(
            val reason: String,
        ) : SsrfResult()

        data class Invalid(
            val reason: String,
        ) : SsrfResult()
    }

    private data class DnsEntry(
        val timestamp: Long,
        val addresses: List<String>,
    )

    private class BlockedRange(
        val cidr: String,
        val description: String,
    ) {
        fun contains(addr: String): Boolean {
            if (cidr == addr) return true

            if (!cidr.contains("/")) {
                return cidr == addr
            }

            return when {
                cidr.contains(":") -> containsIPv6(addr, cidr)
                cidr.contains(".") -> containsIPv4(addr, cidr)
                else -> false
            }
        }

        private fun containsIPv4(
            addr: String,
            cidr: String,
        ): Boolean {
            val slashIdx = cidr.indexOf("/")
            val networkAddr = cidr.substring(0, slashIdx)
            val prefixLen = cidr.substring(slashIdx + 1).toIntOrNull() ?: return false

            val networkNum = ipToLong(networkAddr)
            val addrNum = ipToLong(addr)
            val mask = if (prefixLen == 0) 0L else (0xFFFFFFFFL shl (32 - prefixLen))

            return (addrNum and mask) == (networkNum and mask)
        }

        private fun containsIPv6(
            addr: String,
            cidr: String,
        ): Boolean {
            val slashIdx = cidr.indexOf("/")
            val networkAddr = cidr.substring(0, slashIdx)
            val prefixLen = cidr.substring(slashIdx + 1).toIntOrNull() ?: return false

            val networkBytes = parseIPv6(networkAddr) ?: return false
            val addrBytes = parseIPv6(addr) ?: return false

            val fullBytes = prefixLen / 8
            val remainingBits = prefixLen % 8

            for (i in 0 until fullBytes) {
                if (networkBytes[i] != addrBytes[i]) return false
            }

            if (remainingBits > 0 && fullBytes < 16) {
                val mask = (0xFF shl (8 - remainingBits)).toInt() and 0xFF
                val networkByte = networkBytes[fullBytes].toInt() and 0xFF
                val addrByte = addrBytes[fullBytes].toInt() and 0xFF
                if (networkByte and mask != addrByte and mask) return false
            }

            return true
        }

        private fun ipToLong(addr: String): Long {
            val octets = addr.split(".").map { it.toLongOrNull() ?: 0 }
            return (octets.getOrElse(0) { 0 } shl 24) or
                (octets.getOrElse(1) { 0 } shl 16) or
                (octets.getOrElse(2) { 0 } shl 8) or
                octets.getOrElse(3) { 0 }
        }

        private fun parseIPv6(addr: String): ByteArray? {
            return try {
                val parts = mutableListOf<Short>()
                var doubleColonIdx = -1

                if (addr == "::1") return byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)
                if (addr == "::") return ByteArray(16)

                val ipv4Suffix = addr.indexOf(".")
                if (ipv4Suffix > 0) {
                    val ipv4Part = addr.substring(ipv4Suffix + 1)
                    val ipv4Bytes = ipv4Part.split(".").map { it.toInt() }
                    if (ipv4Bytes.size == 4) {
                        val prefix = addr.substring(0, ipv4Suffix)
                        val prefixParts = prefix.split(":").filter { it.isNotEmpty() }
                        val result = MutableList<Short>(8) { 0 }
                        for (i in prefixParts.indices) {
                            result[i] = prefixParts[i].toUShort(16).toShort()
                        }
                        result[6] = ((ipv4Bytes[0] shl 8) or ipv4Bytes[1]).toShort()
                        result[7] = ((ipv4Bytes[2] shl 8) or ipv4Bytes[3]).toShort()
                        return result.map { it.toByte() }.toByteArray()
                    }
                }

                val segments = addr.split(":")
                for (i in segments.indices) {
                    if (segments[i].isEmpty()) {
                        if (doubleColonIdx == -1) {
                            doubleColonIdx = i
                        }
                    } else {
                        parts.add(segments[i].toUShort(16).toShort())
                    }
                }

                val result = ByteArray(16)
                val prefixLen = if (doubleColonIdx == -1) parts.size else doubleColonIdx
                for (i in 0 until prefixLen) {
                    result[i * 2] = (parts[i].toInt() shr 8).toByte()
                    result[i * 2 + 1] = parts[i].toInt().toByte()
                }

                if (doubleColonIdx != -1) {
                    val suffixStart = doubleColonIdx + (8 - parts.size)
                    for (i in parts.indices) {
                        val idx = (doubleColonIdx + i) * 2
                        if (idx < 16) {
                            result[idx] = (parts[prefixLen + i].toInt() shr 8).toByte()
                            result[idx + 1] = parts[prefixLen + i].toInt().toByte()
                        }
                    }
                }

                result
            } catch (e: Exception) {
                null
            }
        }
    }
}
