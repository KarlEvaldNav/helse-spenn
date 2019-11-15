package no.nav.helse.spenn.overforing

import no.nav.helse.spenn.oppdrag.OppdragStateDTO
import no.nav.helse.spenn.oppdrag.toOppdrag
import org.slf4j.LoggerFactory

class UtbetalingService(val oppdragSender: OppdragMQSender) {
    companion object {
        private val log = LoggerFactory.getLogger(UtbetalingService::class.java)
    }

    fun sendUtbetalingOppdragMQ(oppdrag: OppdragStateDTO) {
        log.info("sender til Oppdragsystemet for sakskompleks ${oppdrag.sakskompleksId} med fagsystemId ${oppdrag.utbetalingsreferanse}")
        oppdragSender.sendOppdrag(oppdrag.toOppdrag())
    }

}