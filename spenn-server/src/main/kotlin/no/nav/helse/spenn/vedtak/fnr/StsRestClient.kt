package no.nav.helse.spenn.vedtak.fnr

import no.nav.helse.spenn.ServiceUser
import no.nav.helse.spenn.stsRestBaseUrl
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.Base64

class StsRestClient(private val serviceUser: ServiceUser, private val stsBaseUrl: String = stsRestBaseUrl) {
    private var cachedOidcToken: Token? = null

    fun token(): String {
        if (Token.shouldRenew(cachedOidcToken)) {
            val encodedAuth =
                String(Base64.getEncoder().encode("${serviceUser.username}:${serviceUser.password}".toByteArray()))
            val resp = khttp.get(
                url = "$stsBaseUrl/rest/v1/sts/token?grant_type=client_credentials&scope=openid",
                headers = mapOf(
                    "Authorization" to "Basic $encodedAuth",
                    "Accept" to "application/json"
                )
            )
            if (resp.statusCode != 200) {
                throw RuntimeException("StsRestClient response got statusCode ${resp.statusCode}")
            }
            cachedOidcToken = resp.jsonObject.mapToToken()
        }
        return cachedOidcToken!!.accessToken
    }

    private fun JSONObject.mapToToken(): Token {
        return Token(
            getString("access_token"),
            getString("token_type"),
            getInt("expires_in")
        )
    }

    data class Token(val accessToken: String, val type: String, val expiresIn: Int) {
        // expire 10 seconds before actual expiry. for great margins.
        val expirationTime: LocalDateTime = LocalDateTime.now().plusSeconds(expiresIn - 10L)

        companion object {
            fun shouldRenew(token: Token?) =
                token == null || isExpired(token)

            private fun isExpired(token: Token): Boolean {
                return token.expirationTime.isBefore(LocalDateTime.now())
            }
        }
    }
}
