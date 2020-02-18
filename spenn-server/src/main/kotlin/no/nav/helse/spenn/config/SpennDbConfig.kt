package no.nav.helse.spenn.config

import io.ktor.config.ApplicationConfig

data class SpennDbConfig(
        val jdbcUrl: String,
        val maximumPoolSize: Int,
        val minimumIdle: Int = 1,
        val vaultEnabled: Boolean,
        val vaultPostgresBackend: String // vault mount-path (i.e: postgresql/preprod-fss)
) {
    companion object {
        @io.ktor.util.KtorExperimentalAPI
        fun from(cfg: ApplicationConfig) = SpennDbConfig(
                jdbcUrl = cfg.property("datasource.url").getString(),
                maximumPoolSize = cfg.property("datasource.hikari.maximum-pool-size").getString().toInt(),
                minimumIdle = cfg.property("datasource.hikari.minimum-idle").getString().toInt(),
                vaultEnabled = true,
                vaultPostgresBackend = cfg.property("datasource.vault.mountpath").getString()
        )
    }
}
