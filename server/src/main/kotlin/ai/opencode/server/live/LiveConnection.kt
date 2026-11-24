package ai.opencode.server.live

import ai.opencode.core.strings.localized
import ai.opencode.server.OpenCodeClientError
import ai.opencode.server.OpenCodePairingLink
import ai.opencode.server.OpenCodeServer
import ai.opencode.server.R
import ai.opencode.server.ServerLinkStore
import ai.opencode.server.retry.RequestIdempotency
import ai.opencode.server.retry.RetryPolicy
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.seconds

/**
 * Whether this address answers as this server. Probes race every address and the first verified
 * one wins; retrying a dead address would hold that choice up, so a probe is sent once. A
 * redirect (a captive portal), a page that isn't the V2 info, or a 401 (another server, or a
 * password that no longer works) doesn't count. With a password, the answer itself proves the
 * server holds it; without one, an address on someone else's network could be any OpenCode
 * server, so its own address list must include one of ours.
 */
internal suspend fun requestHealth(server: OpenCodeServer) {
    val info = request<ServerInfoPayload>(
        "/api/info", timeout = ServerLinkStore.probeTimeout, retry = RetryPolicy.once,
        followsRedirects = false, isConnectionProbe = true, server = server,
    )
    if (!info.identifies(server)) throw OpenCodeClientError("A different OpenCode server answered at this address.")
}

internal suspend fun requestRedeemPairing(link: OpenCodePairingLink): OpenCodeServer {
    // Which address the phone can reach depends on its network, so every listed one is
    // tried at once. The code is single-use: at most one redemption succeeds.
    val candidates = link.redeemOrigins
    val outcome: Result<OpenCodeServer> = coroutineScope {
        val results = Channel<Result<OpenCodeServer>>(Channel.UNLIMITED)
        for (origin in candidates) {
            launch {
                // The connect route is unauthenticated; the single-use code is the credential.
                val anonymous = OpenCodeServer(url = origin, username = "opencode", password = "")
                val result = try {
                    // The server spends the code as the request arrives, so giving up early leaves
                    // only a used code. A GET, but it spends the code: never sent twice, never redirected.
                    val response = perform(
                        "/auth/connect/${link.code}", timeout = 15.seconds, idempotency = RequestIdempotency.Unsafe,
                        retry = RetryPolicy.once, followsRedirects = false, isConnectionProbe = true, server = anonymous,
                    )
                    val session = decoded(response.data, decode = PairingSessionPayload::decode)
                    Result.success(OpenCodeServer(url = origin, username = "opencode", password = session.token, name = link.name ?: session.deviceName))
                } catch (error: OpenCodeClientError) {
                    Result.failure(error)
                }
                results.send(result)
            }
        }
        val failures = mutableListOf<OpenCodeClientError>()
        repeat(candidates.size) {
            val result = results.receive()
            if (result.isSuccess) {
                coroutineContext.cancelChildren()
                return@coroutineScope result
            }
            failures += result.exceptionOrNull() as OpenCodeClientError
        }
        Result.failure(pairingFailure(failures))
    }
    val server = outcome.getOrElse { throw it }
    // Other reachable addresses (LAN, tailnet) let the phone reconnect when it changes
    // networks. The server's own list is optional: an older server without it still pairs.
    val reported = try { request<InfoPayload>("/api/info", server = server).urls ?: emptyList() } catch (_: OpenCodeClientError) { emptyList() }
    // The code's own addresses are preferred: one `opencode pair --url` named is used whenever
    // it answers, even when the tunnel the server also reports answers sooner.
    val preferred = usableAddresses(candidates)
    val alternates = usableAddresses(candidates + reported).filter { it != server.url }
    return server.copy(alternateURLs = alternates, preferredURLs = preferred)
}

/** Addresses without a trailing slash, loopback or anything that isn't an HTTP(S) address, deduplicated. */
private fun usableAddresses(addresses: List<String>): List<String> {
    val seen = hashSetOf<String>()
    val usable = mutableListOf<String>()
    for (address in addresses) {
        val trimmed = address.trim('/')
        if (OpenCodeServer.isLoopback(trimmed)) continue
        if (OpenCodeServer(url = trimmed, username = "", password = "").endpoint("/") == null) continue
        if (seen.add(trimmed)) usable += trimmed
    }
    return usable
}

/** The most telling reason no address redeemed the code: a server that answered beats one that couldn't be reached. */
private fun pairingFailure(failures: List<OpenCodeClientError>): OpenCodeClientError {
    val details = failures.flatMap { it.connectionFailures }
    if (failures.any { it.statusCode == 401 }) {
        return OpenCodeClientError(
            localized(R.string.server_error_pairing_expired),
            statusCode = 401, connectionFailures = details,
        )
    }
    if (failures.any { it.statusCode == 404 }) {
        return OpenCodeClientError(
            localized(R.string.server_error_pairing_unsupported),
            statusCode = 404, connectionFailures = details,
        )
    }
    val failure = failures.lastOrNull() ?: OpenCodeClientError(OpenCodeClientError.UNREACHABLE_MESSAGE)
    return failure.copy(connectionFailures = details)
}
