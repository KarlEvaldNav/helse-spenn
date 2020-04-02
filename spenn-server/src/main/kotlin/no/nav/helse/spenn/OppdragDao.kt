package no.nav.helse.spenn

import kotliquery.queryOf
import kotliquery.sessionOf
import kotliquery.using
import javax.sql.DataSource

internal class OppdragDao(private val dataSource: DataSource) {

    fun oppdaterOppdrag(
        avstemmingsnøkkel: Long,
        utbetalingsreferanse: String,
        status: Oppdragstatus,
        beskrivelse: String,
        feilkode: String,
        xmlMessage: String
    ) =
        using(sessionOf(dataSource)) { session ->
            session.run(queryOf(
                "UPDATE oppdrag_ny SET status = ?, beskrivelse = ?, feilkode_oppdrag = ?, oppdrag_response = ? " +
                        "WHERE avstemmingsnokkel = ? AND utbetalingsreferanse = ?",
                status.name, beskrivelse, feilkode, xmlMessage, avstemmingsnøkkel, utbetalingsreferanse
            ).asUpdate)
        } == 1
}