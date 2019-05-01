package no.nav.helse.spenn.dao

import java.util.*


interface OppdragStateRepository {

    fun insert(oppdragstate: OppdragState): Long

    fun delete(id: Long): OppdragState

    fun findAll(): List<OppdragState>

    fun findById(id: Long?): OppdragState

    fun findBySoknadId(soknadId: UUID) : OppdragState

    fun update(oppdragstate: OppdragState): OppdragState
}