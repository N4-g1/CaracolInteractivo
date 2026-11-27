package cl.ti3042.caracol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolTest {
    @Test fun parsesNoiseAndFailSafeState() {
        val safe = (parseSerial("T,23,0,1,0,0") as SerialMessage.Telemetry).reading
        assertEquals(0, safe.percent)
        assertTrue(safe.hidden)
        assertFalse(safe.armed)
        assertFalse(safe.alarm)

        val loud = (parseSerial("T,24,1023,1,1,1") as SerialMessage.Telemetry).reading
        assertEquals(100, loud.percent)
        assertTrue(loud.alarm)
        assertTrue(loud.armed)
    }

    @Test fun checksFrameSyntaxAndRanges() {
        listOf("T,0,1024,0,0,1", "T,-1,100,0,0,1", "T,4294967296,100,0,0,1",
            "T,1,20,2,0,1", "T,1,20,0,0", "A,60001,OK", "A,1,INVALID", "M,2")
            .forEach { assertNull(it, parseSerial(it)) }
    }

    @Test fun matchesExpectedAcknowledgements() {
        assertEquals(SerialMessage.Ack(6, "OK"), parseSerial("A,6,OK"))
        assertEquals(SerialMessage.Ack(8, "RUIDO"), parseSerial("A,8,RUIDO"))
        assertEquals(SerialMessage.Ack(9, "SEGURO"), parseSerial("A,9,SEGURO"))
        assertEquals(SerialMessage.Ready, parseSerial("LISTO"))
        assertEquals(SerialMessage.Boot, parseSerial("BOOT"))
    }

    @Test fun rejectsInvalidIpv4AndCertificateFingerprints() {
        val pin = "ab".repeat(32)
        val endpoint = Endpoint.parse(" 192.168.0.7#$pin ")
        assertEquals("192.168.0.7", endpoint.ip)
        assertEquals("AB".repeat(32), endpoint.pin)
        listOf("192.168.0.0#A", "192.168.0.256#$pin", "192.168.0.-1#$pin",
            "192.168.0.1#"+ "x".repeat(64), "http://192.168.0.1#$pin").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { Endpoint.parse(invalid) }
        }
    }
}
