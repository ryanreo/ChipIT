package com.chipit.app

import com.chipit.app.agent.RiskyActionClassifier
import com.chipit.app.agent.ToolCall
import com.chipit.app.model.DeviceFitCalculator
import com.chipit.app.model.DeviceFitStatus
import com.chipit.app.model.DeviceResources
import com.chipit.app.model.GgufMetadataParser
import com.chipit.app.model.HfModelSummary
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ChipITCoreTests {

    @Test
    fun deviceFit_evaluatesThresholdsCorrectly() {
        val device = DeviceResources(totalRamMb = 8000, availableRamMb = 4500, freeStorageMb = 10_000)

        // <= 60% of 8000 (4800 MB) -> FITS
        assertEquals(
            DeviceFitStatus.FITS,
            DeviceFitCalculator.evaluateFit(fileSizeBytes = 1_500L * 1024 * 1024, estRamMb = 2500, device = device)
        )

        // 60-80% of 8000 (4801..6400 MB) -> TIGHT
        assertEquals(
            DeviceFitStatus.TIGHT,
            DeviceFitCalculator.evaluateFit(fileSizeBytes = 3_500L * 1024 * 1024, estRamMb = 5500, device = device)
        )

        // > 80% of 8000 -> WONT_FIT
        assertEquals(
            DeviceFitStatus.WONT_FIT,
            DeviceFitCalculator.evaluateFit(fileSizeBytes = 5_000L * 1024 * 1024, estRamMb = 7000, device = device)
        )
    }

    @Test
    fun ggufParser_readsBinaryHeaderAndMetadata() {
        val tmp = File.createTempFile("qwen2.5-1.5b-instruct-q4_k_m", ".gguf")
        try {
            FileOutputStream(tmp).use { out ->
                val buf = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN)
                buf.putInt(0x46554747) // "GGUF"
                buf.putInt(3)          // version 3
                buf.putLong(0L)        // tensor_count
                buf.putLong(1L)        // kv_count

                val key = "general.architecture".toByteArray(Charsets.UTF_8)
                buf.putLong(key.size.toLong())
                buf.put(key)
                buf.putInt(8) // GGUF_TYPE_STRING
                val valBytes = "qwen2".toByteArray(Charsets.UTF_8)
                buf.putLong(valBytes.size.toLong())
                buf.put(valBytes)

                out.write(buf.array(), 0, buf.position())
            }
            val meta = GgufMetadataParser.parse(tmp)
            assertTrue(meta.isValidGguf)
            assertEquals(3, meta.version)
            assertEquals("qwen2", meta.architecture)
            assertEquals("Q4_K_M", meta.quantization)
            assertEquals(1.5, meta.paramCountB, 0.01)
        } finally {
            tmp.delete()
        }
    }

    @Test
    fun toolCallAndRiskClassifier_workAsExpected() {
        val json = Json { ignoreUnknownKeys = true }
        val tapCall = json.decodeFromString<ToolCall>("""{"tool":"tap","args":{"node_id":12}}""")
        assertEquals("tap", tapCall.tool)
        assertEquals(12, tapCall.argInt("node_id"))
        assertFalse(RiskyActionClassifier.isRisky(tapCall, "Search"))
        assertTrue(RiskyActionClassifier.isRisky(tapCall, "Send message"))
        assertTrue(RiskyActionClassifier.isRisky(tapCall, "Pay \$20.00"))
        assertTrue(RiskyActionClassifier.isRisky(tapCall, "Delete account"))
    }

    @Test
    fun hfModelSummary_handlesBooleanAndStringGatedValues() {
        val json = Json { ignoreUnknownKeys = true }
        val payload = """
            [
              {"id":"open/model","downloads":100,"gated":false},
              {"id":"auto/model","downloads":200,"gated":"auto"},
              {"id":"manual/model","downloads":300,"gated":"manual"},
              {"id":"omitted/model","downloads":400}
            ]
        """.trimIndent()
        val parsed = json.decodeFromString<List<HfModelSummary>>(payload)
        assertEquals(4, parsed.size)
        assertFalse(parsed[0].isGated)
        assertTrue(parsed[1].isGated)
        assertTrue(parsed[2].isGated)
        assertFalse(parsed[3].isGated)
    }
}
