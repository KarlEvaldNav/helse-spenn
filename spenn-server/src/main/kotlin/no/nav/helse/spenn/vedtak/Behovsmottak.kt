package no.nav.helse.spenn.vedtak

import com.fasterxml.jackson.databind.JsonNode
import io.confluent.kafka.serializers.AbstractKafkaAvroSerDeConfig
import io.micrometer.core.instrument.MeterRegistry
import no.nav.helse.spenn.KafkaEnvironment
import no.nav.helse.spenn.ServiceUser
import no.nav.helse.spenn.appsupport.VEDTAK
import no.nav.helse.spenn.oppdrag.UtbetalingsOppdrag
import no.nav.helse.spenn.oppdrag.dao.OppdragService
import no.nav.helse.spenn.oppdrag.dao.SanityCheckException
import no.nav.helse.spenn.vedtak.fnr.AktorNotFoundException
import no.nav.helse.spenn.vedtak.fnr.AktørTilFnrMapper
import org.apache.kafka.clients.CommonClientConfigs
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.SaslConfigs
import org.apache.kafka.common.config.SslConfigs
import org.apache.kafka.common.errors.WakeupException
import org.apache.kafka.common.serialization.Serde
import org.apache.kafka.streams.KafkaStreams
import org.apache.kafka.streams.StreamsBuilder
import org.apache.kafka.streams.StreamsConfig
import org.apache.kafka.streams.Topology
import org.apache.kafka.streams.errors.LogAndFailExceptionHandler
import org.apache.kafka.streams.kstream.Consumed
import org.apache.kafka.streams.kstream.KStream
import org.apache.kafka.streams.kstream.Produced
import org.postgresql.util.PSQLException
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.SQLIntegrityConstraintViolationException
import java.time.Duration
import java.util.Properties
import javax.annotation.PostConstruct

class KafkaStreamsConfig(
    val oppdragService: OppdragService,
    val meterRegistry: MeterRegistry,
    private val aktørTilFnrMapper: AktørTilFnrMapper,
    private val env: KafkaEnvironment,
    private val serviceUser: ServiceUser
) {

    companion object {
        private val log = LoggerFactory.getLogger(KafkaStreamsConfig::class.java)
    }

    private fun mottakslogikk(): Topology {
        val builder = StreamsBuilder()

        builder.consumeTopic(SYKEPENGER_RAPID_TOPIC)
            .filter { _, value -> value != null }
            .filter { _, value -> value.skalOppfyllesAvOss("Utbetaling") }
            .filter { _, value -> !value.hasNonNull("@løsning") }
            .filter { _, value -> value.hasNonNull("utbetalingsreferanse") }
            .filter { _, value -> value.hasNonNull("aktørId") }
            .mapValues { _, value ->
                try {
                    value to SpennOppdragFactory
                        .lagOppdragFraBehov(value, aktørTilFnrMapper.tilFnr(value["aktørId"].asText()!!))
                } catch (err: AktorNotFoundException) {
                    log.error("aktør finnes ikke, skipper melding", err)
                    null
                }
            }
            .filter { _, value ->
                value != null
            }
            .mapValues { _, value ->
                value as Pair<JsonNode, UtbetalingsOppdrag>
            }
            .mapValues { _, (originalMessage, utbetaling) -> originalMessage to saveInitialOppdragState(utbetaling) }

        return builder.build()
    }

    private fun JsonNode.skalOppfyllesAvOss(type: String) =
        this["@behov"]?.let {
            if (it.isArray) {
                it.map { b -> b.asText() }.any { t -> t == type }
            } else it.asText() == type
        } ?: false

    @PostConstruct
    fun offsetReset() {
        log.info("Running offset reset of ${SYKEPENGER_RAPID_TOPIC.name} to 1559340000000")
        val configProperties = Properties()
        configProperties[ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG] = env.bootstrapServersUrl
        configProperties[ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG] =
            "org.apache.kafka.common.serialization.StringDeserializer"
        configProperties[ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG] =
            "org.apache.kafka.common.serialization.StringDeserializer"
        configProperties[ConsumerConfig.GROUP_ID_CONFIG] = env.appId
        configProperties[ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG] = false
        configProperties[ConsumerConfig.AUTO_OFFSET_RESET_CONFIG] = "earliest"
        if (!env.plainTextKafka) {
            configProperties[SaslConfigs.SASL_MECHANISM] = "PLAIN"
            configProperties[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "SASL_PLAINTEXT"
            configProperties[SaslConfigs.SASL_JAAS_CONFIG] =
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${serviceUser.username}\" password=\"${serviceUser.password}\";"
            configProperties[CommonClientConfigs.SECURITY_PROTOCOL_CONFIG] = "SASL_SSL"
            configProperties[SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG] =
                File(env.truststorePath!!).absolutePath
            configProperties[SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG] = env.truststorePassword
        }
        val offsetConsumer = KafkaConsumer<String, String>(configProperties)
        offsetConsumer.subscribe(listOf(SYKEPENGER_RAPID_TOPIC.name), object : ConsumerRebalanceListener {
            override fun onPartitionsAssigned(partitions: MutableCollection<TopicPartition>) {
                for (p in partitions) {
                    log.info("assigned to ${p.topic()} to partion: ${p.partition()}")
                    val offsetsForTimes = offsetConsumer.offsetsForTimes(mapOf(p to 1559340000000))
                    log.info("offset for times ${offsetsForTimes.entries}")
                    offsetsForTimes.forEach { (topicPartition, offsetAndTimestamp) ->
                        if (offsetAndTimestamp != null) {
                            log.info("reset offset to ${offsetAndTimestamp.offset()}")
                            offsetConsumer.seek(topicPartition, offsetAndTimestamp.offset())
                            offsetConsumer.commitSync()
                        }
                    }

                }
            }

            override fun onPartitionsRevoked(partitions: MutableCollection<TopicPartition>) {
                log.info("partitions $partitions revoked")
            }
        })
        try {
            val records = offsetConsumer.poll(Duration.ofSeconds(5))
            for (record in records) {
                log.info("offset ${record.offset()}")
            }
        } catch (ex: WakeupException) {
            log.error("Exception caught " + ex.message)
        } finally {
            offsetConsumer.close()
            log.info("Closing OffsetConsumer")
        }
    }

    fun kafkaStreams(topology: Topology): KafkaStreams {
        val streamConfig = if (env.plainTextKafka) streamConfigPlainTextKafka() else streamConfig(
            env.appId, env.bootstrapServersUrl,
            serviceUser.username to serviceUser.password,
            env.truststorePath to env.truststorePassword
        )
        return KafkaStreams(topology, streamConfig)
    }

    private fun saveInitialOppdragState(utbetaling: UtbetalingsOppdrag) {
        return try {
            oppdragService.lagreNyttOppdrag(utbetaling)
        } catch (e: SQLIntegrityConstraintViolationException) {
            log.error("skipping duplicate for key ${utbetaling.utbetalingsreferanse}")
            meterRegistry.counter(VEDTAK, "status", "DUPLIKAT").increment()
        } catch (e: PSQLException) { // TODO : FIX
            if (e.sqlState == "23505") {
                log.error("skipping duplicate for key ${utbetaling.utbetalingsreferanse}")
                meterRegistry.counter(VEDTAK, "status", "DUPLIKAT").increment()
            } else {
                throw e
            }
        } catch (e: SanityCheckException) {
            log.error("utbetaling med ref=${utbetaling.utbetalingsreferanse} bestod ikke sanitycheck: ${e.message}")
        }
    }


    fun <K : Any, V : Any> StreamsBuilder.consumeTopic(topic: Topic<K, V>): KStream<K, V> {
        return consumeTopic(topic, null)
    }

    fun <K : Any, V : Any> StreamsBuilder.consumeTopic(topic: Topic<K, V>, schemaRegistryUrl: String?): KStream<K, V> {
        schemaRegistryUrl?.let {
            topic.keySerde.configure(
                mapOf(
                    AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl
                ), true
            )

            topic.valueSerde.configure(
                mapOf(
                    AbstractKafkaAvroSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG to schemaRegistryUrl
                ), false
            )
        }

        return stream<K, V>(
            topic.name, Consumed.with(topic.keySerde, topic.valueSerde)
        )
    }

    fun <K, V> KStream<K, V>.toTopic(topic: Topic<K, V>) {
        return to(topic.name, Produced.with(topic.keySerde, topic.valueSerde))
    }

    fun streamConsumerStart(): StreamConsumer {
        return streamConsumer(kafkaStreams(mottakslogikk()))
    }

    fun streamConsumer(kafkaStreams: KafkaStreams): StreamConsumer {
        val streamConsumer = StreamConsumer(env.appId, kafkaStreams)
        if (env.streamVedtak) streamConsumer.start()
        return streamConsumer
    }


    private fun streamConfigPlainTextKafka(): Properties = Properties().apply {
        log.warn("Using kafka plain text config only works in development!")
        put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, env.bootstrapServersUrl)
        put(StreamsConfig.APPLICATION_ID_CONFIG, env.appId)
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(
            StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
            LogAndFailExceptionHandler::class.java
        )
    }

    private fun streamConfig(
        appId: String,
        bootstrapServers: String,
        credentials: Pair<String?, String?>,
        truststore: Pair<String?, String?>
    ): Properties = Properties().apply {
        put(StreamsConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers)
        put(StreamsConfig.APPLICATION_ID_CONFIG, appId)

        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(
            StreamsConfig.DEFAULT_DESERIALIZATION_EXCEPTION_HANDLER_CLASS_CONFIG,
            LogAndFailExceptionHandler::class.java
        )

        credentials.first?.let {
            log.info("Using user name ${it} to authenticate against Kafka brokers ")
            put(SaslConfigs.SASL_MECHANISM, "PLAIN")
            put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_PLAINTEXT")
            put(
                SaslConfigs.SASL_JAAS_CONFIG,
                "org.apache.kafka.common.security.plain.PlainLoginModule required username=\"${it}\" password=\"${credentials.second}\";"
            )
        }

        truststore.first?.let {
            try {
                put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SASL_SSL")
                put(SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG, File(it).absolutePath)
                put(SslConfigs.SSL_TRUSTSTORE_PASSWORD_CONFIG, truststore.second)
                log.info("Configured '${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}' location ")
            } catch (ex: Exception) {
                log.error("Failed to set '${SslConfigs.SSL_TRUSTSTORE_LOCATION_CONFIG}' location", ex)
            }
        }
    }
}

data class Topic<K, V>(
    val name: String,
    val keySerde: Serde<K>,
    val valueSerde: Serde<V>
)
