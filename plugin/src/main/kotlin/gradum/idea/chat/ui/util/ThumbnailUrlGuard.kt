/*
 * Copyright (c) 2026 Gradum team, some rights reserved.
 * For licensing terms and conditions, see the MIT LICENSE file.
 *
 * ThumbnailUrlGuard.kt  2026-08-16 00:12:50 Changed by gwy
 */
package gradum.idea.chat.ui.util

import gradum.idea.chat.ui.util.ThumbnailUrlGuard.isPrivateV4
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * URL / network-target guard for [ThumbnailImageLoader].
 *
 * Gradum is a local agent — the network is its only outbound channel —
 * and every thumbnail URL we fetch is attacker-influenced: a malicious
 * page can publish any URL as its `favicon` field, the result page's
 * own root favicon path is composed from a Tavily-supplied host, and
 * even a third-party aggregator sees whatever the host string says.
 * The guard sits in front of every socket open and refuses to:
 *
 *  - non-`https` schemes (http would leak bearer cookies via Referer;
 *    `file:` / `data:` / `javascript:` would let a hostile result
 *    pull bytes off the local disk or execute script);
 *  - URLs whose hostname can't be parsed or is empty;
 *  - hostnames that resolve (today, on *this* machine) to any
 *    RFC 1918 / loopback / link-local / CGNAT / multicast / reserved
 *    / benchmarking / TEST-NET / IPv6 ULA / IPv6 link-local /
 *    IPv4-mapped-private address. A single bad answer in the
 *    round-robin is enough to block — partial checks are how SSRF
 *    gets in.
 *
 * The check is split out from the loader so it can be unit-tested
 * without spinning up a fake HTTP server.
 */
internal object ThumbnailUrlGuard {

  /** Outcome of [check]. [Unsafe] carries a short reason for the log. */
  sealed class Check {
    object Safe : Check()
    data class Unsafe(val reason: String) : Check()
  }

  fun check(url: String): Check {
    val uri: URI = try {
      URI(url)
    } catch (uriException: Exception) {
      return Check.Unsafe("not a valid URI: ${uriException.message}")
    }

    val scheme: String? = uri.scheme?.lowercase()

    if (scheme.isNullOrEmpty())
      return Check.Unsafe("not a valid URI: missing or empty scheme")

    if (scheme != "https") return Check.Unsafe("scheme must be https, got '$scheme'")

    val rawHost: String = uri.host?.takeIf { it.isNotBlank() }
      ?: return Check.Unsafe("missing host")
    val host: String = rawHost.trimStart('[').trimEnd(']')
    if (host.isBlank()) return Check.Unsafe("missing host")

    val addresses: Array<InetAddress> = try {
      InetAddress.getAllByName(host)
    } catch (dnsException: Exception) {
      return Check.Unsafe("DNS resolution failed: ${dnsException.message}")
    }
    if (addresses.isEmpty()) return Check.Unsafe("no DNS records for host '$host'")

    val blockedReason: String? = addresses.firstNotNullOfOrNull { address ->
      when (address) {
        is Inet4Address if isPrivateV4(address.address) ->
          "host resolves to private IPv4 ${formatV4(address.address)}"

        is Inet6Address if isPrivateV6(address.address) ->
          "host resolves to private IPv6 ${formatV6(address.address)}"

        else -> null
      }
    }
    return if (blockedReason != null) Check.Unsafe(blockedReason) else Check.Safe
  }

  /**
   * Returns true for any IPv4 address that is not safely routable on
   * the public internet. Covers RFC 1918, loopback, link-local
   * (incl. cloud-metadata 169.254.169.254), CGNAT, multicast, reserved,
   * benchmarking, and the documentation TEST-NET ranges.
   */
  private fun isPrivateV4(bytes: ByteArray): Boolean {
    if (bytes.size != 4) return false
    val octet0: Int = bytes[0].toInt() and 0xFF
    val octet1: Int = bytes[1].toInt() and 0xFF
    val octet2: Int = bytes[2].toInt() and 0xFF
    return when {
      octet0 == 0 -> true
      octet0 == 10 -> true
      octet0 == 100 && octet1 in 64..127 -> true
      octet0 == 127 -> true
      octet0 == 169 && octet1 == 254 -> true
      octet0 == 172 && octet1 in 16..31 -> true
      octet0 == 192 && octet1 == 0 && octet2 == 0 -> true
      octet0 == 192 && octet1 == 168 -> true
      octet0 == 198 && (octet1 == 18 || octet1 == 19) -> true
      octet0 == 198 && octet1 == 51 && octet2 == 100 -> true
      octet0 == 203 && octet1 == 0 && octet2 == 113 -> true
      octet0 in 224..239 -> true
      octet0 >= 240 -> true
      else -> false
    }
  }

  /**
   * Returns true for IPv6 addresses that should not be reached from
   * a public client: unspecified, loopback, link-local, ULA, and
   * IPv4-mapped private addresses (the last via recursive [isPrivateV4]).
   */
  private fun isPrivateV6(bytes: ByteArray): Boolean {
    if (bytes.size != 16) return false

    // The checks below rely on `bytes[i] == 0` being true for the
    // first 15 bytes of `::1`, which the `Byte` unsigned-style
    // comparison handles correctly (the high bit is 0 for both
    // signed 0 and the literal `0.toByte()`).
    val firstFifteenAreZero: Boolean = (0 until 15).all { bytes[it] == 0.toByte() }

    // `::1` (loopback): first 15 bytes zero, last byte 1.
    if (firstFifteenAreZero && bytes[15] == 1.toByte()) return true

    // `::` (unspecified): all 16 bytes zero. The previous
    // implementation used `bytes.all { it == 0 }` for both this and
    // the loopback check, which can never match `::1` because the
    // last byte is 1, not 0 — a real bug we just hit.
    if (firstFifteenAreZero && bytes[15] == 0.toByte()) return true

    // fe80::/10 (link-local).
    if (bytes[0] == 0xFE.toByte() && (bytes[1].toInt() and 0xC0) == 0x80) return true

    // fc00::/7 (unique-local).
    if ((bytes[0].toInt() and 0xFE) == 0xFC) return true

    // IPv4-mapped IPv6: ::ffff:a.b.c.d. Recurse through the IPv4
    // check so a private IPv4 hidden in the suffix is caught.
    if (bytes[0] == 0.toByte() && bytes[1] == 0.toByte() &&
      bytes[2] == 0.toByte() && bytes[3] == 0.toByte() &&
      bytes[4] == 0.toByte() && bytes[5] == 0.toByte() &&
      bytes[6] == 0.toByte() && bytes[7] == 0.toByte() &&
      bytes[8] == 0.toByte() && bytes[9] == 0.toByte() &&
      bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
    ) {
      return isPrivateV4(byteArrayOf(bytes[12], bytes[13], bytes[14], bytes[15]))
    }
    return false
  }

  private fun formatV4(bytes: ByteArray): String =
    bytes.joinToString(".") { (it.toInt() and 0xFF).toString() }

  private fun formatV6(bytes: ByteArray): String =
    Inet6Address.getByAddress(bytes).hostAddress.orEmpty()
}
