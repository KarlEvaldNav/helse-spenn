package no.nav.helse.spenn

import com.fasterxml.jackson.databind.JsonNode
import com.ibm.mq.jms.MQQueue
import no.nav.helse.rapids_rivers.JsonMessage
import no.nav.helse.rapids_rivers.RapidsConnection
import no.nav.helse.rapids_rivers.River
import no.nav.helse.rapids_rivers.asLocalDate
import no.nav.helse.spenn.oppdrag.OppdragXml
import no.trygdeetaten.skjema.oppdrag.Oppdrag
import org.slf4j.LoggerFactory
import java.time.Instant
import java.time.ZoneId
import javax.jms.Connection
import kotlin.math.roundToInt

class Utbetalinger(
    rapidsConnection: RapidsConnection,
    jmsConnection: Connection,
    sendQueue: String,
    private val replyTo: String
) : River.PacketListener {

    private val log = LoggerFactory.getLogger(this::class.java)

    private val jmsSession = jmsConnection.createSession()
    private val producer = jmsSession.createProducer(jmsSession.createQueue(sendQueue))

    init {
        River(rapidsConnection).apply {
            validate { it.requireValue("@event_name", "behov") }
            validate { it.requireAll("@behov", listOf("Utbetaling")) }
            validate { it.forbid("@løsning") }
            validate { it.require("maksdato", JsonNode::asLocalDate) }
            validate { it.requireKey("@id", "fødselsnummer", "utbetalingsreferanse",
                "utbetalingslinjer", "organisasjonsnummer", "forlengelse", "saksbehandler") }
        }.register(this)
    }

    override fun onPacket(packet: JsonMessage, context: RapidsConnection.MessageContext) {
        log.info("løser utbetalingsbehov id=${packet["@id"].asText()}")
        val utbetalingslinjer = Utbetalingslinjer(
            utbetalingsreferanse = packet["utbetalingsreferanse"].asText(),
            organisasjonsnummer = packet["organisasjonsnummer"].asText(),
            fødselsnummer = packet["fødselsnummer"].asText(),
            forlengelse = packet["forlengelse"].asBoolean()
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
        val avstemmingsnøkkel = Avstemmingsnøkkel.opprett(nå)
        val oppdrag = OppdragBuilder(
            saksbehandler = packet["saksbehandler"].asText(),
            maksdato = packet["maksdato"].asLocalDate(),
            avstemmingsnøkkel = avstemmingsnøkkel,
            utbetalingslinjer = utbetalingslinjer,
            tidspunkt = nå
        ).build()

        sendOppdrag(oppdrag)

        packet["@løsning"] = mapOf(
            "Utbetaling" to mapOf(
                "status" to Utbetalingstatus.OVERFØRT,
                "overføringstidspunkt" to nå.atZone(ZoneId.systemDefault()),
                "avstemmingsnøkkel" to avstemmingsnøkkel
            )
        )
        context.send(packet.toJson())
    }

    private fun sendOppdrag(oppdrag: Oppdrag) {
        val oppdragXml = OppdragXml.marshal(oppdrag)
        val message = jmsSession.createTextMessage(oppdragXml)
        message.jmsReplyTo = MQQueue(replyTo)
        producer.send(message)
    }
}
