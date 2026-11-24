package com.chipit.app.model

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

data class GgufMetadata(
    val isValidGguf: Boolean,
    val version: Int = 0,
    val architecture: String = "unknown",
    val modelName: String? = null,
    val contextLength: Int = 2048,
    val quantization: String = "Q4_K_M",
    val paramCountB: Double = 0.0
)

/**
 * Lightweight binary GGUF v2/v3 header parser.
 * Reads magic ("GGUF"), version, tensor_count, metadata_kv_count and extracts
 * key model metadata without loading tensor weights into memory.
 */
object GgufMetadataParser {
    private const val GGUF_MAGIC = 0x46554747 // "GGUF" little-endian

    fun parse(file: File): GgufMetadata {
        val fallbackQuant = inferQuantFromName(file.name)
        val fallbackParamsB = inferParamsB(file.name, file.length())

        if (!file.exists() || file.length() < 24) {
            return GgufMetadata(
                isValidGguf = false,
                quantization = fallbackQuant,
                paramCountB = fallbackParamsB
            )
        }

        return runCatching {
            RandomAccessFile(file, "r").use { raf ->
                val header = ByteArray(24)
                raf.readFully(header)
                val buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val magic = buf.int
                if (magic != GGUF_MAGIC) {
                    return GgufMetadata(
                        isValidGguf = false,
                        quantization = fallbackQuant,
                        paramCountB = fallbackParamsB
                    )
                }
                val version = buf.int
                /* val tensorCount = */ buf.long
                val kvCount = buf.long.coerceAtMost(256L)

                var arch = "llama"
                var name: String? = null
                var ctxLen = 2048

                for (i in 0 until kvCount.toInt()) {
                    if (raf.filePointer >= file.length() - 8) break
                    val key = readGgufString(raf) ?: break
                    val valueType = readIntLe(raf) ?: break
                    when (key) {
                        "general.architecture" -> if (valueType == 8) arch = readGgufString(raf) ?: arch else skipValue(raf, valueType)
                        "general.name" -> if (valueType == 8) name = readGgufString(raf) else skipValue(raf, valueType)
                        "$arch.context_length" -> if (valueType == 4 || valueType == 5) ctxLen = readIntLe(raf) ?: ctxLen else skipValue(raf, valueType)
                        else -> if (!skipValue(raf, valueType)) break
                    }
                }

                GgufMetadata(
                    isValidGguf = true,
                    version = version,
                    architecture = arch,
                    modelName = name,
                    contextLength = ctxLen.coerceIn(512, 131072),
                    quantization = fallbackQuant,
                    paramCountB = fallbackParamsB
                )
            }
        }.getOrElse {
            GgufMetadata(
                isValidGguf = false,
                quantization = fallbackQuant,
                paramCountB = fallbackParamsB
            )
        }
    }

    fun inferQuantFromName(name: String): String {
        val regex = Regex(
            """(IQ[1-4]_(?:XXS|XS|NL|S|M)|Q[2-8]_(?:K_[SML]|K|0|1)|F16|BF16)""",
            RegexOption.IGNORE_CASE
        )
        return regex.find(name)?.value?.uppercase() ?: "Q4_K_M"
    }

    fun inferParamsB(name: String, sizeBytes: Long): Double {
        val regex = Regex("""(\d+(?:\.\d+)?)\s*[bB]\b""")
        val parsed = regex.find(name)?.groupValues?.getOrNull(1)?.toDoubleOrNull()
        if (parsed != null && parsed > 0.0) return parsed
        // Rough estimate for Q4 (~0.65 GB per billion parameters)
        val gb = sizeBytes / (1024.0 * 1024.0 * 1024.0)
        return ((gb / 0.65) * 10.0).toInt() / 10.0
    }

    private fun readIntLe(raf: RandomAccessFile): Int? {
        val b = ByteArray(4)
        if (raf.read(b) != 4) return null
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int
    }

    private fun readLongLe(raf: RandomAccessFile): Long? {
        val b = ByteArray(8)
        if (raf.read(b) != 8) return null
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).long
    }

    private fun readGgufString(raf: RandomAccessFile): String? {
        val len = readLongLe(raf) ?: return null
        if (len < 0 || len > 1_048_576) return null
        val bytes = ByteArray(len.toInt())
        raf.readFully(bytes)
        return String(bytes, Charsets.UTF_8)
    }

    private fun skipValue(raf: RandomAccessFile, valueType: Int): Boolean {
        return when (valueType) {
            0, 1, 7 -> { raf.skipBytes(1); true } // uint8, int8, bool
            2, 3 -> { raf.skipBytes(2); true }    // uint16, int16
            4, 5, 6 -> { raf.skipBytes(4); true } // uint32, int32, float32
            10, 11, 12 -> { raf.skipBytes(8); true } // uint64, int64, float64
            8 -> readGgufString(raf) != null      // string
            9 -> { // array: elemType (4) + count (8)
                val elemType = readIntLe(raf) ?: return false
                val count = readLongLe(raf) ?: return false
                if (count < 0 || count > 100_000) return false
                for (j in 0 until count.toInt()) {
                    if (!skipValue(raf, elemType)) return false
                }
                true
            }
            else -> false
        }
    }
}
