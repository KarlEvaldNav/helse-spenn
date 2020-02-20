package no.nav.helse.spenn.oppdrag.dao

import no.nav.helse.spenn.core.FagOmraadekode
import no.nav.helse.spenn.core.defaultObjectMapper
import no.nav.helse.spenn.oppdrag.AvstemmingMapper
import no.nav.helse.spenn.oppdrag.SatsTypeKode
import no.nav.helse.spenn.oppdrag.TransaksjonStatus
import no.nav.helse.spenn.oppdrag.UtbetalingsOppdrag
import no.nav.helse.spenn.oppdrag.toOppdragRequest
import no.nav.helse.spenn.oppdrag.toSimuleringRequest
import no.nav.helse.spenn.simulering.SimuleringResult
import no.nav.helse.spenn.simulering.SimuleringStatus.OK
import no.nav.system.os.tjenester.simulerfpservice.simulerfpservicegrensesnitt.SimulerBeregningRequest
import no.trygdeetaten.skjema.oppdrag.Oppdrag
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.time.LocalDateTime
import javax.sql.DataSource

private val log = LoggerFactory.getLogger(OppdragService::class.java.name)

class OppdragService(dataSource: DataSource) {

    private val repository = TransaksjonRepository(dataSource)

    inner class Transaksjon internal constructor(dto: TransaksjonDTO) {
        private var transaksjonDTO = dto

        internal val dto: TransaksjonDTO get() = transaksjonDTO

        val oppdragRequest: Oppdrag
            get() {
                require(transaksjonDTO.status == TransaksjonStatus.SENDT_OS)
                return transaksjonDTO.toOppdragRequest()
            }

        val simuleringRequest: SimulerBeregningRequest
            get() {
                require(transaksjonDTO.status == TransaksjonStatus.STARTET)
                return transaksjonDTO.toSimuleringRequest()
            }

        fun forberedSendingTilOS() {
            performSanityCheck()
            transaksjonDTO = repository.oppdaterTransaksjonMedStatusOgNøkkel(transaksjonDTO, TransaksjonStatus.SENDT_OS, LocalDateTime.now())
        }

        fun stopp(feilmelding: String?) {
            transaksjonDTO = repository.stoppTransaksjon(transaksjonDTO, feilmelding)
        }

        fun lagreOSResponse(status: TransaksjonStatus, xml: String, feilmelding: String?) {
            repository.lagreOSResponse(transaksjonDTO.utbetalingsreferanse, transaksjonDTO.nokkel!!, status, xml, feilmelding)
            transaksjonDTO = repository.findByRefAndNokkel(transaksjonDTO.utbetalingsreferanse, transaksjonDTO.nokkel!!)
        }

        override fun toString() = "Transaksjon(utbetalingsreferanse=${transaksjonDTO.utbetalingsreferanse}, " +
                "avstemmingsnøkkel=${transaksjonDTO.nokkel})"

        private fun performSanityCheck() {
            val utbetaling = transaksjonDTO.utbetalingsOppdrag.utbetaling
            utbetaling?.utbetalingsLinjer?.forEach {
                if (it.satsTypeKode != SatsTypeKode.DAGLIG) {
                    throw SanityCheckException("satsTypeKode er ${it.satsTypeKode}. Vi har ikke logikk for å sanity-sjekke dette.")
                }
                val maksDagsats = maksTillattDagsats()
                if (it.sats > maksDagsats) {
                    throw SanityCheckException("dagsats ${it.sats} som er høyere enn begrensningen på $maksDagsats")
                }
            }
        }

        private fun erAnnulering() = transaksjonDTO.utbetalingsOppdrag.utbetaling == null

        fun oppdaterSimuleringsresultat(result: SimuleringResult) {
            if (result.simulering == null && result.status == OK) require(erAnnulering())
            val status = if (result.status == OK) TransaksjonStatus.SIMULERING_OK
            else TransaksjonStatus.SIMULERING_FEIL
            transaksjonDTO = repository.oppdaterTransaksjonMedStatusOgSimuleringResult(
                    transaksjonDTO,
                    status,
                    defaultObjectMapper.writeValueAsString(result)
            )
        }

        fun markerSomAvstemt() {
            repository.markerSomAvstemt(transaksjonDTO)
            transaksjonDTO = transaksjonDTO.copy(avstemt = true)
        }

    }

    fun annulerUtbetaling(behov: Utbetalingsbehov) {
        annulerUtbetaling(behov.tilUtbetalingsOppdrag())
    }

    internal fun annulerUtbetaling(oppdrag: UtbetalingsOppdrag) {
        require(oppdrag.utbetaling == null)
        val transaksjoner = repository.findByRef(oppdrag.utbetalingsreferanse)
        if (transaksjoner.size > 1 && transaksjoner.any { it.utbetalingsOppdrag.utbetaling == null }) throw AlleredeAnnulertException("Annulleringen finnes allerede.")
        require(1 == transaksjoner.size)
        val originaltOppdrag = transaksjoner.first().utbetalingsOppdrag
        require(oppdrag.oppdragGjelder == originaltOppdrag.oppdragGjelder)

        requireNotNull(originaltOppdrag.utbetaling)
        require(originaltOppdrag.utbetaling.utbetalingsLinjer.isNotEmpty())
        repository.insertNyTransaksjon(oppdrag.copy(
                statusEndringFom = originaltOppdrag.utbetaling.utbetalingsLinjer.minBy { it.datoFom }!!.datoFom,
                opprinneligOppdragTom = originaltOppdrag.utbetaling.utbetalingsLinjer.maxBy { it.datoTom }!!.datoTom
        ))
    }

    fun lagreNyttOppdrag(behov: Utbetalingsbehov) {
        lagreNyttOppdrag(behov.tilUtbetalingsOppdrag())
    }

    internal fun lagreNyttOppdrag(oppdrag: UtbetalingsOppdrag)
    {
        requireNotNull(oppdrag.utbetaling)
        require(oppdrag.utbetaling.utbetalingsLinjer.isNotEmpty())

        val eksisterendeTranser = repository.findByRef(utbetalingsreferanse = oppdrag.utbetalingsreferanse)
        if (eksisterendeTranser.isEmpty()) {
            log.info("Helt nytt oppdrag med utbetalingsreferanse=${oppdrag.utbetalingsreferanse}")
            repository.insertNyttOppdrag(oppdrag)
        } else {
            log.info("Utvidelsesoppdrag (eksisterende utbetalingsreferanse=${oppdrag.utbetalingsreferanse}). " +
                    "Det finnes ${eksisterendeTranser.size} transaksjoner fra før")
            sanityCheckUtvidelse(eksisterendeTranser, oppdrag)
            repository.insertNyTransaksjon(oppdrag.copy(
                    utbetaling = oppdrag.utbetaling.copy(
                            utbetalingsLinjer = oppdrag.utbetaling.utbetalingsLinjer.map {
                                it.copy(erEndring = true)
                            }
                    )
            ))
        }
    }

    // For å hente ut for å lagre respons fra OS
    fun hentTransaksjon(utbetalingsreferanse: String, avstemmingsNøkkel: LocalDateTime) =
            Transaksjon(repository.findByRefAndNokkel(utbetalingsreferanse, avstemmingsNøkkel))

    fun hentNyeOppdrag(limit: Int) =
            repository.findAllByStatus(TransaksjonStatus.STARTET, limit).map { Transaksjon(it) }

    fun hentFerdigsimulerte(limit: Int) =
            repository.findAllByStatus(TransaksjonStatus.SIMULERING_OK, limit).map { Transaksjon(it) }

    fun hentEnnåIkkeAvstemteTransaksjonerEldreEnn(maks: LocalDateTime) =
            repository.findAllNotAvstemtWithAvstemmingsnokkelNotAfter(maks).map { Transaksjon(it) }

    companion object {
        internal fun sanityCheckUtvidelse(eksisterende: List<TransaksjonDTO>, nyttOppdrag: UtbetalingsOppdrag) {
            requireNotNull(nyttOppdrag.utbetaling)
            val sisteEksisterende = eksisterende.sortedBy { it.created }.last()

            val errorStringPrefix = "Siste transasksjon med referanse ${nyttOppdrag.utbetalingsreferanse}"

            if (sisteEksisterende.utbetalingsOppdrag.utbetaling == null)
                throw SanityCheckException("$errorStringPrefix var en annulering. Vet ikke hvordan håndtere dette")

            val linjerPåForrige = sisteEksisterende.utbetalingsOppdrag.utbetaling.utbetalingsLinjer
            val linjerPåNy = nyttOppdrag.utbetaling.utbetalingsLinjer

            val erEnkelUtvidelse = linjerPåForrige.size == 1 && linjerPåNy.size == 1 &&
                    linjerPåForrige.first().id == linjerPåNy.first().id
            val forrigeLinje = linjerPåForrige[0]
            val nyLinje = linjerPåNy[0]

            if (!erEnkelUtvidelse)
                throw SanityCheckException("$errorStringPrefix er ikke en enkel utvidelse")
            if (sisteEksisterende.status != TransaksjonStatus.FERDIG)
                throw SanityCheckException("$errorStringPrefix har status=${sisteEksisterende.status}. Vet ikke hvordan håndtere dette")
            if (sisteEksisterende.utbetalingsOppdrag.utbetaling.organisasjonsnummer != nyttOppdrag.utbetaling.organisasjonsnummer)
                throw SanityCheckException("$errorStringPrefix har organisasjonsnummer forskjellig fra utvidelse")
            if (sisteEksisterende.utbetalingsOppdrag.oppdragGjelder != nyttOppdrag.oppdragGjelder)
                throw SanityCheckException("$errorStringPrefix har annen 'oppdragGjelder'")
            if (forrigeLinje.datoFom != nyLinje.datoFom)
                throw SanityCheckException("$errorStringPrefix har annen 'datoFom'")
            if (!nyLinje.datoTom.isAfter(forrigeLinje.datoTom))
                throw SanityCheckException("$errorStringPrefix har ikke 'datoTom' etter forrige datoTom")
            if (nyLinje.sats != forrigeLinje.sats)
                throw SanityCheckException("$errorStringPrefix har ulik dagsats")
        }
    }
}

internal fun UtbetalingsOppdrag.lagPåSidenSimuleringsrequest() =
        TransaksjonDTO(
                id = -1,
                utbetalingsreferanse = this.utbetalingsreferanse,
                nokkel = LocalDateTime.now(),
                utbetalingsOppdrag = this,
                created = LocalDateTime.now()).toSimuleringRequest()

fun Utbetalingsbehov.lagPåSidenSimuleringsrequest(erUtvidelse: Boolean = false) =
        this.tilUtbetalingsOppdrag(erUtvidelse).lagPåSidenSimuleringsrequest()

fun List<OppdragService.Transaksjon>.lagAvstemmingsmeldinger() =
        AvstemmingMapper(this.map { it.dto }, FagOmraadekode.SYKEPENGER_REFUSJON).lagAvstemmingsMeldinger()

class SanityCheckException(message: String) : Exception(message)

class AlleredeAnnulertException(message: String) : Exception(message)

// TODO ? Konfig? Sykepenger er maks 6G, maksTillattDagsats kan ikke være lavere enn dette.
private fun maksTillattDagsats(G: Int = 100_000, hverdagerPrÅr: Int = 260) = BigDecimal(6.5 * G / hverdagerPrÅr)
