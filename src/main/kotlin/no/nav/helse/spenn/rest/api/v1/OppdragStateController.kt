package no.nav.helse.spenn.rest.api.v1

import no.nav.helse.spenn.oppdrag.dao.OppdragStateService

import no.nav.helse.spenn.oppdrag.OppdragStateDTO
import no.nav.helse.spenn.oppdrag.dao.OppdragStateStatus
import no.nav.security.oidc.api.Protected
import no.nav.security.oidc.context.OIDCRequestContextHolder
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.*

// NB: Sync tilgangsstyring med helse-spade
@Protected
@RestController
@RequestMapping("/api/v1/oppdrag")
class OppdragStateController(val oppdragStateService: OppdragStateService,
                             val oidcRequestContextHolder: OIDCRequestContextHolder) {

    companion object {
        private val LOG = LoggerFactory.getLogger(OppdragStateController::class.java)
        private val AUDIT_LOG = LoggerFactory.getLogger("auditLogger")
    }

    @GetMapping("/soknad/{soknadId}")
    fun getOppdragStateBySoknadId(@PathVariable soknadId: UUID): OppdragStateDTO {
        LOG.info("Rest retrieve for soknadId: ${soknadId}")
        AUDIT_LOG.info("Bruker=${currentNavIdent()} slår opp søknadId=${soknadId}")
        return oppdragStateService.fetchOppdragState(soknadId)
    }

    @GetMapping("/{id}")
    fun getOpppdragStateById(@PathVariable id: Long): OppdragStateDTO {
        LOG.info("Rest retrieve for id: ${id}")
        AUDIT_LOG.info("Bruker=${currentNavIdent()} slår opp oppdragId=${id}")
        return oppdragStateService.fetchOppdragStateById(id)
    }

    @PutMapping("/{id}")
    fun updateOppdragState(@PathVariable id: Long, @RequestBody dto: OppdragStateDTO): OppdragStateDTO {
        LOG.info("Rest update for id: ${id}")
        AUDIT_LOG.info("Bruker=${currentNavIdent()} oppdaterer oppdragId=${id}")
        return oppdragStateService.saveOppdragState(dto)
    }

    @GetMapping("/status/{status}")
    fun getOppdragStateByStatus(@PathVariable status: OppdragStateStatus): List<OppdragStateDTO> {
        LOG.info("Rest retrieve for status: ${status}")
        AUDIT_LOG.info("Bruker=${currentNavIdent()} slår opp oppdrag på status=${status}")
        return oppdragStateService.fetchOppdragStateByStatus(status)
    }

    private fun currentNavIdent() = oidcRequestContextHolder.oidcValidationContext.getClaims("ourissuer").get("NAVident")

}
