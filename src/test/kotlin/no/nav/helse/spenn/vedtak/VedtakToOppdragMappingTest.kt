package no.nav.helse.spenn.vedtak

import no.nav.helse.integrasjon.okonomi.oppdrag.AksjonsKode
import no.nav.helse.integrasjon.okonomi.oppdrag.SatsTypeKode
import no.nav.helse.spenn.oppdrag.OppdragsLinje
import no.nav.helse.spenn.oppdrag.UtbetalingsOppdrag
import no.trygdeetaten.skjema.oppdrag.Oppdrag
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.*

class VedtakToOppdragMappingTest {

    @Test
    fun `mapping av et enkelt vedtak`() {
        val vedtak = etEnkeltVedtak()

        val oppdragsLinje = OppdragsLinje(
                id = "0/0",
                sats = BigDecimal.valueOf(1234L),
                satsTypeKode = SatsTypeKode.DAGLIG,
                datoFom = vedtak.vedtaksperioder[0].fom,
                datoTom = vedtak.vedtaksperioder[0].tom,
                utbetalesTil = vedtak.vedtaksperioder[0].fordeling[0].mottager
        )
        val målbilde = UtbetalingsOppdrag(
                id = vedtak.søknadId,
                operasjon = AksjonsKode.OPPDATER,
                oppdragGjelder = vedtak.fnr,
                oppdragslinje = listOf(oppdragsLinje)
        )

        val faktisk = tilOppdrag(vedtak)

        Assertions.assertEquals(målbilde, faktisk)
    }

    private fun etEnkeltVedtak(): Vedtak {
        return Vedtak(
                søknadId = UUID.randomUUID().toString(),
                fnr = "12345612345",
                vedtaksperioder = listOf(Vedtaksperiode(
                        fom = LocalDate.of(2020, 1, 15),
                        tom = LocalDate.of(2020, 1, 30),
                        dagsats = BigDecimal.valueOf(1234L),
                        fordeling = listOf(Fordeling(
                                mottager = "897654321",
                                andel = BigDecimal.valueOf(100L),
                                kontonummer = "betyr ikke noe enda"
                        ))
                ))
        )
    }
}