package com.mackseoul.vcardnative.data.backup

import com.mackseoul.vcardnative.data.profile.StrictJson
import org.junit.Assert.*
import org.junit.Test

class BackupStrictJsonTest {
    @Test fun arraysAreOptInAndExistingDepth24RemainsDefault() {
        val bytes = StrictJson.encode(listOf(mapOf("x" to listOf(null, true, 1L, "😀"))))
        assertEquals(listOf(mapOf("x" to listOf(null, true, 1L, "😀"))),
            StrictJson.decode(bytes, maxDepth = 16, allowArrays = true))
        assertThrows(IllegalArgumentException::class.java) { StrictJson.decode(bytes) }
        val sixteen = ("[".repeat(16) + "null" + "]".repeat(16)).toByteArray()
        assertNotNull(StrictJson.decode(sixteen, maxDepth = 16, allowArrays = true))
        val seventeen = ("[".repeat(17) + "]".repeat(17)).toByteArray()
        assertThrows(IllegalArgumentException::class.java) {
            StrictJson.decode(seventeen, maxDepth = 16, allowArrays = true)
        }
        assertNotNull(StrictJson.decode(seventeen, allowArrays = true))
        val objects = ("{\"x\":".repeat(17) + "null" + "}".repeat(17)).toByteArray()
        assertNotNull(StrictJson.decode(objects))
        assertThrows(IllegalArgumentException::class.java) { StrictJson.decode(objects, maxDepth = 16) }
    }

    @Test fun arraysRetainStrictUnicodeIntegerFramingAndAggregateBounds() {
        for (text in listOf("[1,]", "[,1]", "[1 2]", "[01]", "[1.5]", "[1e2]", "[NaN]",
            "[\"\\uD800\"]", "[{\"x\":1,\"x\":2}]", "[[]]trailing",
            "[" + List(4096) { "null" }.joinToString(",") + "]")) {
            assertThrows(IllegalArgumentException::class.java) {
                StrictJson.decode(text.toByteArray(), maxDepth = 16, allowArrays = true)
            }
        }
    }
}
