package no.nav.helse.spenn.rest.api.v1

import no.nav.helse.spenn.dao.OppdragStateService
import no.nav.helse.spenn.oppdrag.OppdragStateDTO
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.*

@RestController
@RequestMapping("/api/v1")
@Profile(value = ["!prod"])
class OppdragStateController(val oppdragStateService: OppdragStateService) {

    companion object {
        private val LOG = LoggerFactory.getLogger(OppdragStateController::class.java)
    }

    @GetMapping("/oppdrag/soknad/{soknadId}")
    fun getOppdragStateBySoknadId(@PathVariable soknadId: UUID): OppdragStateDTO {
        LOG.info("Rest retrieve for soknadId: ${soknadId}")
        return oppdragStateService.fetchOppdragState(soknadId)
    }

    @GetMapping("/oppdrag/{id}")
    fun getOpppdragStateById(@PathVariable id: Long): OppdragStateDTO {
        LOG.info("Rest retrieve for id: ${id}")
        return oppdragStateService.fetchOppdragStateById(id)
    }

}