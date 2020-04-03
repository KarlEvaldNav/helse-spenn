package no.nav.helse.spenn

import no.nav.helse.spenn.oppdrag.OppdragXml
import no.nav.virksomhet.tjenester.avstemming.meldinger.v1.*
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

internal class OppdragDto(
    private val fødselsnummer: String,
    private val utbetalingsreferanse: String,
    private val opprettet: LocalDateTime,
    private val status: Oppdragstatus,
    private val totalbeløp: Int,
    oppdragXml: String?
) {
    internal companion object {
        private val tidsstempel =
            DateTimeFormatter.ofPattern("yyyy-MM-dd-HH.mm.ss.SSSSSS")

        fun detaljer(liste: List<OppdragDto>) = liste.mapNotNull { it.somDetalj() }

        fun totaldata(liste: List<OppdragDto>) = Totaldata().apply {
            liste.summer { antall, beløp, fortegn ->
                totalAntall = antall
                totalBelop = beløp
                this.fortegn = fortegn
            }
        }

        fun grunnlagsdata(liste: List<OppdragDto>) = Grunnlagsdata().apply {
            val beløpEtterStatus = liste.groupBy { it.status }
            beløpEtterStatus.summer(Oppdragstatus.AKSEPTERT) { antall, beløp, fortegn ->
                godkjentAntall = antall
                godkjentBelop = beløp
                godkjentFortegn = fortegn
            }
            beløpEtterStatus.summer(Oppdragstatus.AKSEPTERT_MED_FEIL) { antall, beløp, fortegn ->
                varselAntall = antall
                varselBelop = beløp
                varselFortegn = fortegn
            }
            beløpEtterStatus.summer(Oppdragstatus.AVVIST) { antall, beløp, fortegn ->
                avvistAntall = antall
                avvistBelop = beløp
                avvistFortegn = fortegn
            }
            beløpEtterStatus.summer(Oppdragstatus.OVERFØRT, Oppdragstatus.FEIL) { antall, beløp, fortegn ->
                manglerAntall = antall
                manglerBelop = beløp
                manglerFortegn = fortegn
            }
        }

        private fun List<OppdragDto>.summer(block: (Int, BigDecimal, Fortegn) -> Unit) {
            totalbeløp(this).also { block(size, it.toBigDecimal(), if (it >= 0) Fortegn.T else Fortegn.F) }
        }

        private fun Map<Oppdragstatus, List<OppdragDto>>.summer(vararg status: Oppdragstatus, block: (Int, BigDecimal, Fortegn) -> Unit) {
            status.mapNotNull { this[it] }.flatten().summer(block)
        }

        private fun totalbeløp(liste: List<OppdragDto>) = liste.sumBy { it.totalbeløp }
    }

    private val kvittering = oppdragXml?.let { OppdragXml.unmarshal(it) }

    private fun somDetalj(): Detaljdata? {
        return Detaljdata().apply {
            detaljType = detaljType() ?: return null
            offnr = fødselsnummer
            avleverendeTransaksjonNokkel = utbetalingsreferanse
            tidspunkt = tidsstempel.format(opprettet)
            if (kvittering != null && (detaljType == DetaljType.AVVI || detaljType == DetaljType.VARS)) {
                meldingKode = kvittering.mmel.kodeMelding
                alvorlighetsgrad = kvittering.mmel.alvorlighetsgrad
                tekstMelding = kvittering.mmel.beskrMelding
            }
        }
    }

    private fun detaljType() = when (status) {
        Oppdragstatus.FEIL,
        Oppdragstatus.OVERFØRT -> DetaljType.MANG
        Oppdragstatus.AVVIST -> DetaljType.AVVI
        Oppdragstatus.AKSEPTERT_MED_FEIL -> DetaljType.VARS
        else -> null
    }
}
