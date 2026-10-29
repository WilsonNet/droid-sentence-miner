package io.github.wilsonnet.droidsentenceminer.dict

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

class JsonArrayStreamTest {

    @Test
    fun streamsTermBankRows() {
        val bank = """[["猫","ねこ","n","",0,[{"type":"structured-content","content":"cat"}],1,""],""" +
            """ ["犬","いぬ","n","",0,["dog"],2,""]]"""
        val rows = jsonArrayItems(bank.byteInputStream()).map { it as JsonArray }.toList()
        assertEquals(listOf("猫", "犬"), rows.map { it[0].jsonPrimitive.content })
        assertEquals("""[{"type":"structured-content","content":"cat"}]""", rows[0][5].toString())
    }

    @Test
    fun streamsLargeBankWithoutHoldingIt() {
        // 200k rows with ~1 KB glossaries each (~200 MB of JSON) would not fit as one String in a
        // phone heap; here we only check rows come out one at a time and in order.
        val n = 20_000
        val text = (0 until n).joinToString(",", "[", "]") { """["w$it","r","","",0,["${"x".repeat(200)}"],$it,""]""" }
        var count = 0
        jsonArrayItems(text.byteInputStream()).forEachIndexed { i, item ->
            assertEquals("w$i", (item as JsonArray)[0].jsonPrimitive.content)
            count++
        }
        assertEquals(n, count)
    }
}
