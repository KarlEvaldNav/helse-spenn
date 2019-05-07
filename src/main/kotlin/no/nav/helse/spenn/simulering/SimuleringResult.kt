package no.nav.helse.spenn.simulering

import no.nav.helse.spenn.oppdrag.SatsTypeKode
import no.nav.helse.spenn.oppdrag.UtbetalingsType
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate

data class SimuleringResult(val status: Status,
                            val feilMelding: String = "",
                            val mottaker: Mottaker? = null)

data class Mottaker(val gjelderId: String,
                    val gjelderNavn: String,
                    val datoBeregnet: String,
                    val totalBelop: BigDecimal,
                    val periodeList: List<Periode>)

data class Periode(val id: String,
                   val faktiskFom: LocalDate,
                   val faktiskTom: LocalDate,
                   val oppdragsId: Long,
                   val forfall: LocalDate,
                   val utbetalesTilId: String,
                   val utbetalesTilNavn: String,
                   val konto: String,
                   val belop: BigDecimal,
                   val sats: BigDecimal,
                   val typeSats: SatsTypeKode,
                   val antallSats: BigDecimal,
                   val uforegrad: BigInteger,
                   val utbetalingsType: UtbetalingsType)

enum class Status {
    OK,
    FEIL
}