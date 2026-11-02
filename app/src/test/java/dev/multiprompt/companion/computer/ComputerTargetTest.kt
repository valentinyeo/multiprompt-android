package dev.multiprompt.companion.computer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ComputerTargetTest {
    @Test fun emptyAndBrokenAddressesFallBackToNothing() {
        assertNull(ComputerTarget.parse(null))
        assertNull(ComputerTarget.parse(""))
        assertNull(ComputerTarget.parse("   "))
        assertNull(ComputerTarget.parse("10.0.0.1:notaport"))
        assertNull(ComputerTarget.parse(":6080"))
        assertNull(ComputerTarget.parse("127.0.0.1:0"))
        assertNull(ComputerTarget.parse("127.0.0.1:70000"))
        assertNull(ComputerTarget.parse("host name:6080"))
    }

    @Test fun hostAndPortSurviveEveryWrittenForm() {
        assertEquals(ComputerTarget("127.0.0.1", 6080), ComputerTarget.parse("127.0.0.1:6080"))
        assertEquals(ComputerTarget("127.0.0.1", 6080), ComputerTarget.parse("http://127.0.0.1:6080/"))
        assertEquals(ComputerTarget("viewer.local", 6081), ComputerTarget.parse(" viewer.local:6081 "))
        assertEquals(ComputerTarget("viewer.local", 6080), ComputerTarget.parse("viewer.local"))
        assertEquals("127.0.0.1:6080", ComputerTarget.Default.value)
    }
}
