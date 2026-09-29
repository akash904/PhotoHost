package io.github.akash904.photohost.server

import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.uri

/**
 * A single shared bearer token for M2.
 *
 * Accepted two ways, and the second one is the point: `Authorization: Bearer` for curl and
 * programmatic clients, and an `HttpOnly` cookie for browsers, so that `<img>` and `<video>` tags
 * work with **no token in the URL**. Tokens in query strings leak through server logs, browser
 * history and `Referer` headers, so the only place one ever appears in a URL here is the one-shot
 * `/pair` hand-off.
 *
 * M6 replaces this with per-device tokens stored as SHA-256 only, minted by short-lived pairing
 * codes and individually revocable.
 */
class Auth(private val token: String) {

    /**
     * Failed attempts per client address.
     *
     * On a LAN this was unnecessary. Exposed to the internet it is not: a 256-bit token is
     * unguessable in theory, but an endpoint that answers unlimited attempts instantly is still an
     * open invitation, and it makes the server a free oracle for anyone scanning the address.
     *
     * Deliberately in memory only. A restart clearing the counters is acceptable -- the attacker
     * has to make the server restart, which is harder than waiting -- and persisting it would mean
     * a disk write on every failed guess, which is its own denial of service.
     */
    private val failures = java.util.concurrent.ConcurrentHashMap<String, Attempts>()

    private class Attempts(@Volatile var count: Int, @Volatile var windowStart: Long)

    /**
     * True when [address] has failed too often recently and should be refused without the token
     * even being compared.
     */
    fun isThrottled(address: String): Boolean {
        val entry = failures[address] ?: return false
        val now = System.currentTimeMillis()
        if (now - entry.windowStart > WINDOW_MS) {
            failures.remove(address)
            return false
        }
        return entry.count >= MAX_FAILURES
    }

    fun recordFailure(address: String) {
        val now = System.currentTimeMillis()
        failures.compute(address) { _, existing ->
            when {
                existing == null -> Attempts(1, now)
                now - existing.windowStart > WINDOW_MS -> Attempts(1, now)
                else -> existing.also { it.count++ }
            }
        }
    }

    fun recordSuccess(address: String) {
        failures.remove(address)
    }

    fun isAuthorized(call: ApplicationCall): Boolean {
        val header = call.request.headers["Authorization"]
            ?.takeIf { it.startsWith(BEARER, ignoreCase = true) }
            ?.substring(BEARER.length)
            ?.trim()
        if (constantTimeEquals(header, token)) return true
        return constantTimeEquals(call.request.cookies[COOKIE], token)
    }

    /** True for endpoints deliberately left open: the liveness probe and the pairing hand-off. */
    fun isPublicPath(call: ApplicationCall): Boolean {
        val path = call.request.uri.substringBefore('?')
        return path == "/health" || path == "/pair"
    }

    fun matches(candidate: String?): Boolean = constantTimeEquals(candidate, token)

    companion object {
        const val COOKIE = "photohost"
        private const val BEARER = "Bearer "

        /** Ten wrong guesses per address, then a five minute freeze. */
        private const val MAX_FAILURES = 10
        private const val WINDOW_MS = 5 * 60 * 1000L

        /**
         * Length-independent comparison. Returning early on the first differing byte would leak
         * the token a character at a time to anyone who can measure response timing.
         */
        fun constantTimeEquals(a: String?, b: String?): Boolean {
            if (a == null || b == null) return false
            var diff = a.length xor b.length
            for (i in a.indices) {
                diff = diff or (a[i].code xor b[if (i < b.length) i else 0].code)
            }
            return diff == 0
        }
    }
}
