package dev.gpicalter.net

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * The phone's HTTP stack, rebuilt for tests: OkHttp, a trust manager that accepts exactly the
 * pinned certificate, hostname verification off only when pinned, the bearer token on every call.
 * Mirrors gpicAlter's net/Pinning.kt and di/AppContainer.http (commit d43de56).
 */
object PhoneClient {

    val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    class PinnedTrustManager(expectedFingerprint: String) : X509TrustManager {
        private val expected = expectedFingerprint.lowercase().replace(":", "")

        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("server presented no certificate")
            val actual = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
                .joinToString("") { "%02x".format(it) }
            if (actual != expected) {
                throw CertificateException("certificate fingerprint $actual does not match the pinned $expected")
            }
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    fun okhttp(fingerprint: String?, token: String?, followRedirects: Boolean = true): OkHttpClient {
        val base = OkHttpClient.Builder()
            .followRedirects(followRedirects)
            .addInterceptor { chain ->
                val req = chain.request()
                chain.proceed(
                    if (token != null && req.header("Authorization") == null) {
                        req.newBuilder().header("Authorization", "Bearer $token").build()
                    } else {
                        req
                    },
                )
            }
        if (fingerprint == null) return base.build()
        val trust = PinnedTrustManager(fingerprint)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), java.security.SecureRandom()) }
        return base.sslSocketFactory(ctx.socketFactory, trust).hostnameVerifier { _, _ -> true }.build()
    }

    fun ktor(ok: OkHttpClient, followRedirects: Boolean = true): HttpClient = HttpClient(OkHttp) {
        engine { preconfigured = ok }
        // Ktor follows redirects itself, independently of OkHttp's setting.
        this.followRedirects = followRedirects
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
    }
}
