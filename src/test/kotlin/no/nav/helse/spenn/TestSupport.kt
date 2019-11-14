package no.nav.helse.spenn

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock
import com.nimbusds.jwt.JWTClaimsSet
import io.micrometer.core.instrument.MockClock
import io.micrometer.prometheus.PrometheusConfig
import io.micrometer.prometheus.PrometheusMeterRegistry
import io.prometheus.client.CollectorRegistry
import no.nav.helse.spenn.config.SpennApiAuthConfig
import no.nav.helse.spenn.rest.SpennApiEnvironment
import no.nav.helse.spenn.rest.api.v1.AuditSupport
import no.nav.helse.spenn.simulering.SimuleringService
import no.nav.helse.spenn.vedtak.Fodselsnummer
import no.nav.helse.spenn.vedtak.Utbetalingsbehov
import no.nav.helse.spenn.vedtak.Utbetalingslinje
import no.nav.helse.spenn.vedtak.fnr.AktørTilFnrMapper
import no.nav.security.token.support.test.JwkGenerator
import no.nav.security.token.support.test.JwtTokenGenerator
import org.apache.kafka.streams.KafkaStreams
import org.mockito.Mockito
import org.mockito.stubbing.OngoingStubbing
import java.net.URL
import java.time.LocalDate
import java.util.*

const val requiredGroupMembership = "12345678-abcd-abcd-eeff-1234567890ab"

fun testSpennApiAuthConfig() : SpennApiAuthConfig {
    return SpennApiAuthConfig(
            acceptedAudience= JwtTokenGenerator.AUD,
            discoveryUrl = URL("http://localhost:33333/.well-known/openid-configuration"),
            requiredGroup = requiredGroupMembership)
}

fun mockApiEnvironment() = SpennApiEnvironment(
        kafkaStreams = Mockito.mock(KafkaStreams::class.java),
        meterRegistry = PrometheusMeterRegistry(PrometheusConfig.DEFAULT, CollectorRegistry(), MockClock()),
        authConfig = testSpennApiAuthConfig(),
        simuleringService = Mockito.mock(SimuleringService::class.java),
        auditSupport = AuditSupport(),
        aktørTilFnrMapper = Mockito.mock(AktørTilFnrMapper::class.java)
)

fun buildClaimSet(subject: String,
                  issuer: String = JwtTokenGenerator.ISS,
                  audience: String = JwtTokenGenerator.AUD,
                  authLevel: String = JwtTokenGenerator.ACR,
                  expiry: Long = JwtTokenGenerator.EXPIRY,
                  issuedAt: Date = Date(),
                  navIdent: String? = null,
                  groups: List<String>? = null): JWTClaimsSet {
    val builder = JWTClaimsSet.Builder()
            .subject(subject)
            .issuer(issuer)
            .audience(audience)
            .jwtID(UUID.randomUUID().toString())
            .claim("acr", authLevel)
            .claim("ver", "1.0")
            .claim("nonce", "myNonce")
            .claim("auth_time", issuedAt)
            .notBeforeTime(issuedAt)
            .issueTime(issuedAt)
            .expirationTime(Date(issuedAt.time + expiry))
    if (navIdent != null) {
        builder.claim("NAVident", navIdent)
    }
    if (groups != null) {
        builder.claim("groups", groups)
    }
    return builder.build()
}

fun stubOIDCProvider(server: WireMockServer) {
    WireMock.stubFor(WireMock.any(WireMock.urlPathEqualTo("/.well-known/openid-configuration")).willReturn(
            WireMock.okJson("{\"jwks_uri\": \"${server.baseUrl()}/keys\", " +
                    "\"subject_types_supported\": [\"pairwise\"], " +
                    "\"issuer\": \"${JwtTokenGenerator.ISS}\"}")))

    WireMock.stubFor(WireMock.any(WireMock.urlPathEqualTo("/keys")).willReturn(
            WireMock.okJson(JwkGenerator.getJWKSet().toPublicJWKSet().toString())))
}

fun etEnkeltBehov(
    sakskompleksId: UUID = UUID.randomUUID(),
    maksdato: LocalDate = LocalDate.now().plusYears(1)
) = Utbetalingsbehov(
    sakskompleksId = sakskompleksId,
    utbetalingsreferanse = "1001",
    aktørId = "en random aktørid",
    saksbehandler = "yes",
    organisasjonsnummer = "897654321",
    utbetalingslinjer = listOf(
        Utbetalingslinje(
            fom = LocalDate.of(2020, 1, 15),
            tom = LocalDate.of(2020, 1, 30),
            dagsats = 1234.toBigDecimal(),
            grad = 100
        )
    ),
    maksdato = maksdato
)

fun <T> any(): T = Mockito.any<T>()

fun <T> kArgThat(matcher: (T) -> Boolean): T = Mockito.argThat<T>(matcher)

fun <T> kWhen(methodCall : T) : OngoingStubbing<T> =
    Mockito.`when`(methodCall)


class DummyAktørMapper() : AktørTilFnrMapper {
    override fun tilFnr(aktørId: String): Fodselsnummer = aktørId
}
