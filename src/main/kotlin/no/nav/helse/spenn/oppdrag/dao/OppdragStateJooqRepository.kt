package no.nav.helse.spenn.oppdrag.dao

import no.nav.helse.spenn.jooq.Tables.AVSTEMMING
import org.jooq.DSLContext
import no.nav.helse.spenn.jooq.Tables.OPPDRAGSTATE
import no.nav.helse.spenn.jooq.tables.records.AvstemmingRecord
import no.nav.helse.spenn.jooq.tables.records.OppdragstateRecord
import org.jooq.Configuration
import org.jooq.Record
import org.jooq.SelectOnConditionStep
import org.jooq.impl.DSL
import org.jooq.impl.DSL.currentTimestamp
import java.sql.Timestamp
import java.time.LocalDateTime
import java.util.*


class OppdragStateJooqRepository(val jooq: DSLContext): OppdragStateRepository {

    //@Transactional(readOnly = false)
    override fun insert(oppdragstate: OppdragState): OppdragState {
        val id = jooq.transactionResult { conf: Configuration ->
            val dslContext = DSL.using(conf)
            val id =  with(OPPDRAGSTATE) {
                dslContext.insertInto(this)
                        .set(SOKNAD_ID, oppdragstate.soknadId)
                        .set(MODIFIED, currentTimestamp())
                        .set(CREATED, currentTimestamp())
                        .set(UTBETALINGSOPPDRAG, oppdragstate.utbetalingsOppdrag)
                        .set(STATUS, oppdragstate.status.name)
                        .set(OPPDRAGRESPONSE, oppdragstate.oppdragResponse)
                        .set(SIMULERINGRESULT, oppdragstate.simuleringResult)
                        .set(FEILBESKRIVELSE, oppdragstate.feilbeskrivelse)
                        .returning()
                        .fetchOne()
                        .id
            }
            insertAvstemming(oppdragstate.avstemming, id, dslContext)
            id
        }
        return findById(id)
    }

    //@Transactional(readOnly = false)
    override fun delete(id: Long): OppdragState {
        val delete = findById(id)
        jooq.delete(OPPDRAGSTATE)
                .where(OPPDRAGSTATE.ID.equal(id))
                .execute()
        return delete
    }


    //@Transactional(readOnly = true)
    override fun findAll(): List<OppdragState> {
        return selectOppdragStateLeftJoinAvstemmingOnCondition()
                .map { it.into(OPPDRAGSTATE).toOppdragState(it.into(AVSTEMMING)) }
    }

    //@Transactional(readOnly = true)
    override fun findById(id: Long?): OppdragState {
        return selectOppdragStateLeftJoinAvstemmingOnCondition()
                .where(OPPDRAGSTATE.ID.equal(id))
                .fetchOne()
                .map {
                    it.into(OPPDRAGSTATE).toOppdragState(it.into(AVSTEMMING))
                }
    }

    //@Transactional(readOnly = true)
    override fun findAllByStatus(status: OppdragStateStatus, limit: Int): List<OppdragState> {
        return selectOppdragStateLeftJoinAvstemmingOnCondition()
                .where(OPPDRAGSTATE.STATUS.equal(status.name))
                .limit(limit)
                .map { it.into(OPPDRAGSTATE).toOppdragState(it.into(AVSTEMMING)) }
    }


    //@Transactional(readOnly = false)
    override fun update(oppdragstate: OppdragState): OppdragState {
        jooq.transaction { conf: Configuration ->
            val dslContext = DSL.using(conf)
            with(OPPDRAGSTATE) {
                dslContext.update(this)
                        .set(SOKNAD_ID, oppdragstate.soknadId)
                        .set(MODIFIED, currentTimestamp())
                        .set(STATUS, oppdragstate.status.name)
                        .set(UTBETALINGSOPPDRAG, oppdragstate.utbetalingsOppdrag)
                        .set(SIMULERINGRESULT, oppdragstate.simuleringResult)
                        .set(OPPDRAGRESPONSE, oppdragstate.oppdragResponse)
                        .set(FEILBESKRIVELSE, oppdragstate.feilbeskrivelse)
                        .where(ID.equal(oppdragstate.id))
                        .execute()
            }
            updateAvstemming(oppdragstate.avstemming, oppdragstate.id!!, dslContext)
        }
        return findById(oppdragstate.id)
    }

    //@Transactional(readOnly = true)
    override fun findBySoknadId(soknadId: UUID): OppdragState {
        return selectOppdragStateLeftJoinAvstemmingOnCondition()
                .where(OPPDRAGSTATE.SOKNAD_ID.equal(soknadId))
                .fetchOne()
                .map {
                    it.into(OPPDRAGSTATE).toOppdragState(it.into(AVSTEMMING))
                }
    }

    override fun findAllByAvstemtAndStatus(avstemt: Boolean, status:OppdragStateStatus): List<OppdragState> {
        return selectOppdragStateLeftJoinAvstemmingOnCondition()
                .where(OPPDRAGSTATE.STATUS.equal(status.name).and(AVSTEMMING.AVSTEMT.equal(avstemt)))
                .map { it.into(OPPDRAGSTATE).toOppdragState(it.into(AVSTEMMING))}

    }

    override fun findAllNotAvstemtWithAvstemmingsnokkelNotAfter(avstemmingsnokkelMax: LocalDateTime): List<OppdragState> {
        return jooq.select().from(OPPDRAGSTATE)
                .join(AVSTEMMING)
                .on(OPPDRAGSTATE.ID.equal(AVSTEMMING.OPPDRAGSTATE_ID))
                .where(AVSTEMMING.AVSTEMT.equal(false))
                .and(AVSTEMMING.NOKKEL.isNotNull)
                .and(AVSTEMMING.NOKKEL.le(avstemmingsnokkelMax.toTimeStamp()))
                .map { it.into(OPPDRAGSTATE).toOppdragState(it.into(AVSTEMMING))}
    }

    private fun selectOppdragStateLeftJoinAvstemmingOnCondition(): SelectOnConditionStep<Record> {
        return jooq.select().from(OPPDRAGSTATE)
                .leftJoin(AVSTEMMING)
                .on(OPPDRAGSTATE.ID.equal(AVSTEMMING.OPPDRAGSTATE_ID))
    }


    private fun insertAvstemming(avstemming: Avstemming?, oppdragstateId: Long, dslContext: DSLContext) {
        if (avstemming!=null) {
             with(AVSTEMMING) {
                 dslContext.insertInto(this)
                    .set(OPPDRAGSTATE_ID, oppdragstateId)
                    .set(NOKKEL, avstemming.nokkel.toTimeStamp())
                    .set(AVSTEMT, avstemming.avstemt)
                    .execute()
            }
        }
    }

    private fun updateAvstemming(avstemming: Avstemming?, oppdragstateId: Long, dslContext: DSLContext) {
        if (avstemming!=null) {
            with(AVSTEMMING) {
                if (avstemming.id == null) {
                    insertAvstemming(avstemming, oppdragstateId, dslContext)
                }
                else {
                    dslContext.update(this)
                            .set(OPPDRAGSTATE_ID, oppdragstateId)
                            .set(NOKKEL, avstemming.nokkel.toTimeStamp())
                            .set(AVSTEMT, avstemming.avstemt)
                            .where(ID.equal(avstemming.id))
                        .execute()
                }
            }
        }
    }
}

private fun LocalDateTime?.toTimeStamp(): Timestamp? {
   return  if (this != null ) Timestamp.valueOf(this) else null
}

private fun OppdragstateRecord?.toOppdragState(avstemmingRecord: AvstemmingRecord): OppdragState {
    if (this?.id == null) throw OppdragStateNotFound()
    return OppdragState(id=id, soknadId = soknadId, created = created.toLocalDateTime(),
            modified = modified.toLocalDateTime(), utbetalingsOppdrag = utbetalingsoppdrag,
            oppdragResponse = oppdragresponse, status = OppdragStateStatus.valueOf(status),
            simuleringResult = simuleringresult, avstemming = avstemmingRecord.toAvstemming(),
            feilbeskrivelse = feilbeskrivelse)

}

private fun AvstemmingRecord?.toAvstemming(): Avstemming? {
    if (this?.id == null) return null
    return Avstemming(id = id, oppdragstateId = oppdragstateId, nokkel = nokkel.toLocalDateTime(), avstemt = avstemt)
}


class OppdragStateNotFound : Throwable()
