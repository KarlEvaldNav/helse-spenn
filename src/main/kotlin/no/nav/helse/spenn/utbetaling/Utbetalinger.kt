package no.nav.helse.spenn.utbetaling

import com.fasterxml.jackson.databind.JsonNode
import com.ibm.mq.jms.MQQueue
import no.nav.helse.rapids_rivers.JsonMessage
import no.nav.helse.rapids_rivers.RapidsConnection
import no.nav.helse.rapids_rivers.River
import no.nav.helse.rapids_rivers.asLocalDate
import no.nav.helse.spenn.Avstemmingsnøkkel
import no.nav.helse.spenn.Utbetalingslinjer
import no.trygdeetaten.skjema.oppdrag.Oppdrag
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import javax.jms.Connection
import kotlin.math.roundToInt

internal class Utbetalinger(
    rapidsConnection: RapidsConnection,
    jmsConnection: Connection,
    sendQueue: String,
    private val replyTo: String,
    private val oppdragDao: OppdragDao
) : River.PacketListener {

    private companion object {
        private val log = LoggerFactory.getLogger(Utbetalinger::class.java)
        private val sikkerLogg = LoggerFactory.getLogger("tjenestekall")
    }

    private val jmsSession = jmsConnection.createSession()
    private val producer = jmsSession.createProducer(jmsSession.createQueue(sendQueue))

    init {
        River(rapidsConnection).apply {
            validate { it.requireValue("@event_name", "behov") }
            validate { it.requireContains("@behov", "Utbetaling") }
            validate { it.forbid("@løsning") }
            validate { it.require("maksdato", JsonNode::asLocalDate) }
            validate { it.requireKey("@id", "fødselsnummer", "utbetalingsreferanse", "organisasjonsnummer", "saksbehandler") }
            validate { it.requireArray("utbetalingslinjer") {
                requireKey("fom", "tom", "dagsats", "grad")
            } }
            validate { it.interestedIn("forlengelse") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        log.info("løser utbetalingsbehov id=${packet["@id"].asText()}")
        val fødselsnummer = packet["fødselsnummer"].asText()
        val utbetalingsreferanse = packet["utbetalingsreferanse"].asText()
        val utbetalingslinjer = Utbetalingslinjer(
            utbetalingsreferanse = utbetalingsreferanse,
            organisasjonsnummer = packet["organisasjonsnummer"].asText(),
            fødselsnummer = fødselsnummer,
            forlengelse = packet["forlengelse"].asBoolean(false)
        ).apply {
            packet["utbetalingslinjer"].forEach {
                refusjonTilArbeidsgiver(
                    fom = it["fom"].asLocalDate(),
                    tom = it["tom"].asLocalDate(),
                    dagsats = it["dagsats"].asInt(),
                    grad = it["grad"].asDouble().roundToInt()
                )
            }
        }

        if (utbetalingslinjer.isEmpty()) return log.info("ingen utbetalingslinjer id=${packet["@id"].asText()}; ignorerer behov")

        val nå = Instant.now()
        val tidspunkt = nå
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime()
        val avstemmingsnøkkel = Avstemmingsnøkkel.opprett(nå)
        val oppdrag = OppdragBuilder(
            saksbehandler = packet["saksbehandler"].asText(),
            maksdato = packet["maksdato"].asLocalDate(),
            avstemmingsnøkkel = avstemmingsnøkkel,
            utbetalingslinjer = utbetalingslinjer,
            tidspunkt = nå
        ).build()

        if (!oppdragDao.nyttOppdrag(avstemmingsnøkkel, fødselsnummer, tidspunkt, utbetalingsreferanse,
                Oppdragstatus.OVERFØRT, utbetalingslinjer.totalbeløp(), packet.toJson())) {
            packet["@løsning"] = mapOf(
                "Utbetaling" to mapOf(
                    "status" to Oppdragstatus.FEIL,
                    "beskrivelse" to "Kunne ikke opprette nytt Oppdrag (teknisk feil)"
                )
            )
        } else {
            sendOppdrag(oppdrag)
            packet["@løsning"] = mapOf(
                "Utbetaling" to mapOf(
                    "status" to Oppdragstatus.OVERFØRT,
                    "overføringstidspunkt" to tidspunkt,
                    "avstemmingsnøkkel" to avstemmingsnøkkel
                )
            )
        }

        context.send(packet.toJson().also { sikkerLogg.info("sender løsning på utbetaling=$it") })
    }

    private fun sendOppdrag(oppdrag: Oppdrag) {
        val oppdragXml = OppdragXml.marshal(oppdrag)
        val message = jmsSession.createTextMessage(oppdragXml)
        message.jmsReplyTo = MQQueue(replyTo)
        producer.send(message)
    }
}
