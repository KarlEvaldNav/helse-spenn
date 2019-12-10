package no.nav.helse.spenn.grensesnittavstemming

import no.nav.helse.spenn.oppdrag.AvstemmingMapper
import no.nav.helse.spenn.oppdrag.JAXBAvstemmingsdata
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class AvstemmingXMLMappingTest {
    
    @Test
    fun testThatJAXBAvstemmingsdataIsAlive() {
        val avstemmingsdata = AvstemmingMapper.objectFactory.createAvstemmingsdata()
        val generertXml = JAXBAvstemmingsdata().fromAvstemmingsdataToXml(avstemmingsdata)

        assertEquals("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n" +
                "<ns2:avstemmingsdata xmlns:ns2=\"http://nav.no/virksomhet/tjenester/avstemming/meldinger/v1\"/>\n", generertXml)

        val xmlFraAvstemmingsdataFraGenerertXML = JAXBAvstemmingsdata()
            .fromAvstemmingsdataToXml(JAXBAvstemmingsdata().toAvstemmingsdata(generertXml))

        assertEquals(generertXml, xmlFraAvstemmingsdataFraGenerertXML)
    }
}