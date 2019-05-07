package no.nav.helse.spenn.dao

import java.time.LocalDateTime
import java.util.*

data class OppdragState(val id: Long? = null, val soknadId: UUID, val created: LocalDateTime = LocalDateTime.now(),
                        val modified: LocalDateTime = LocalDateTime.now(),
                        val utbetalingsOppdrag: String, val status: OppdragStateStatus = OppdragStateStatus.STARTET,
                        val oppdragResponse: String? = null, val simuleringResult: String? = null)

enum class OppdragStateStatus {
    STARTET,
    SIMULERING_OK,
    SIMULERING_FEIL,
    SENDT_OS,
    FERDIG,
    FEIL
}

