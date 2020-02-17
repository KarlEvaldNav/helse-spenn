package no.nav.helse.spenn.oppdrag

import no.nav.helse.spenn.oppdrag.dao.TransaksjonDTO
import no.nav.helse.spenn.testsupport.etUtbetalingsOppdrag
import no.nav.system.os.tjenester.simulerfpservice.simulerfpservicegrensesnitt.SimulerBeregningRequest
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.io.StringWriter
import java.math.BigDecimal
import java.math.BigInteger
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.xml.bind.JAXBContext
import javax.xml.bind.Marshaller
import kotlin.test.assertNull

class SimulerOppdragMapperTest {

    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    @Test
    fun mapSimuleringsOppdrag() {
        val maksDato = LocalDate.now().plusYears(1).minusDays(50)
        val vedtakFom = LocalDate.now().minusWeeks(2)
        val vedtakTom = LocalDate.now()
        val enOppdragsLinje = UtbetalingsLinje(
            id = "1234567890",
            datoFom = vedtakFom,
            datoTom = vedtakTom,
            sats = BigDecimal.valueOf(1230),
            satsTypeKode = SatsTypeKode.MÅNEDLIG,
            utbetalesTil = "123456789",
            grad = BigInteger.valueOf(100)
        )
        val utbetalingTemplate = etUtbetalingsOppdrag()
        val utbetaling = utbetalingTemplate.copy(
            oppdragGjelder = "12121212345",
            utbetalingsreferanse = "1001",
            utbetaling = utbetalingTemplate.utbetaling!!.copy(
                utbetalingsLinjer = listOf(enOppdragsLinje),
                maksdato = maksDato
            )
        )
        val oppdragState = TransaksjonDTO(
            id = 1L,
            utbetalingsreferanse = "1001",
            utbetalingsOppdrag = utbetaling,
            created = LocalDateTime.now()
        )
        val simuleringRequest = oppdragState.toSimuleringRequest()
        val jaxbContext = JAXBContext.newInstance(SimulerBeregningRequest::class.java)
        val marshaller = jaxbContext.createMarshaller()
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true)
        val stringWriter = StringWriter()
        marshaller.marshal(simuleringRequest, stringWriter)

        Assertions.assertEquals("12121212345", simuleringRequest.request.oppdrag.oppdragGjelderId)
        Assertions.assertEquals("1001", simuleringRequest.request.oppdrag.fagsystemId)
        assertNull(simuleringRequest.request.oppdrag.oppdragslinje.first().utbetalesTilId)
        Assertions.assertEquals(
            "00123456789",
            simuleringRequest.request.oppdrag.oppdragslinje[0].refusjonsInfo.refunderesId
        )
        Assertions.assertEquals(
            maksDato.format(formatter),
            simuleringRequest.request.oppdrag.oppdragslinje[0].refusjonsInfo.maksDato
        )
        Assertions.assertEquals(
            vedtakFom.format(formatter),
            simuleringRequest.request.oppdrag.oppdragslinje[0].refusjonsInfo.datoFom
        )
    }
}