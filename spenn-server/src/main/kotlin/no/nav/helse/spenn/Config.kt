package no.nav.helse.spenn

import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

const val vaultServiceUserBase = "/var/run/secrets/nais.io/service_user"
const val stsRestBaseUrl = "http://security-token-service"
const val ourIssuer = "ourissuer"

val vaultServiceUserBasePath: Path = Paths.get(vaultServiceUserBase)

fun readServiceUserCredentials() = ServiceUser(
    username = Files.readString(vaultServiceUserBasePath.resolve("username")),
    password = Files.readString(vaultServiceUserBasePath.resolve("password"))
)

fun readEnvironment() = Environment(
    simuleringServiceUrl = System.getenv("SIMULERING_SERVICE_URL"),
    stsSoapUrl = System.getenv("SECURITYTOKENSERVICE_URL"),
    aktorRegisteretBaseUrl = System.getenv("AKTORREGISTERET_BASE_URL"),
    auth = AuthEnvironment(
        acceptedAudience = System.getenv("NO_NAV_SECURITY_OIDC_ISSUER_OURISSUER_ACCEPTED_AUDIENCE"),
        discoveryUrl = URL(System.getenv("NO_NAV_SECURITY_OIDC_ISSUER_OURISSUER_DISCOVERYURL")),
        requiredGroup = System.getenv("API_ACCESS_REQUIREDGROUP")
    ),
    db = DbEnvironment(
        jdbcUrl =System.getenv("DATASOURCE_URL"),
        vaultPostgresMountpath = System.getenv("VAULT_POSTGRES_MOUNTPATH")
    )
)

data class ServiceUser(
    val username: String,
    val password: String
)

data class Environment(
    val simuleringServiceUrl: String,
    val stsSoapUrl: String,
    val aktorRegisteretBaseUrl: String,
    val auth: AuthEnvironment,
    val db: DbEnvironment
)

data class AuthEnvironment(
    val acceptedAudience: String,
    val discoveryUrl: URL,
    val requiredGroup: String
)

data class DbEnvironment(
    val jdbcUrl: String,
    val vaultPostgresMountpath: String
)
