package dev.gpicalter.server

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
        const val COOKIE = "gpic"
        private const val BEARER = "Bearer "

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
