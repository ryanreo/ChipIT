package com.chipit.app.model

import com.chipit.app.data.db.ModelFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

@Serializable
data class HfModelSummary(
    val id: String,
    val downloads: Long = 0,
    val likes: Long = 0,
    val tags: List<String> = emptyList(),
    val gated: JsonElement? = null
) {
    val isGated: Boolean
        get() {
            val raw = (gated as? JsonPrimitive)?.content ?: return false
            return raw.isNotBlank() && !raw.equals("false", ignoreCase = true)
        }

    val license: String
        get() = tags.firstOrNull { it.startsWith("license:") }
            ?.removePrefix("license:")
            ?: "See repo card"
}

@Serializable
data class HfTreeEntry(
    val type: String = "",
    val path: String = "",
    val size: Long = 0L,
    val lfs: HfLfsInfo? = null
) {
    val effectiveSizeBytes: Long
        get() = lfs?.size ?: size
}

@Serializable
data class HfLfsInfo(
    val oid: String = "",
    val size: Long = 0L
)

data class HfModelFileCandidate(
    val repoId: String,
    val fileName: String,
    val sizeBytes: Long,
    val sha256: String?,
    val format: ModelFormat,
    val quantization: String,
    val paramCountB: Double,
    val license: String,
    val gated: Boolean,
    val downloadUrl: String,
    val estRamMb: Int,
    val fitStatus: DeviceFitStatus
)

class HuggingFaceApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json { ignoreUnknownKeys = true }

    companion object {
        const val MAX_MOBILE_FILE_BYTES = 8L * 1024L * 1024L * 1024L // ~8 GB
    }

    suspend fun searchMobileModels(
        query: String,
        includeLiteRt: Boolean,
        token: String?,
        limit: Int = 20
    ): Result<List<HfModelSummary>> = withContext(Dispatchers.IO) {
        runCatching {
            val encoded = URLEncoder.encode(query.trim(), "UTF-8")
            val url = if (includeLiteRt) {
                "https://huggingface.co/api/models?author=litert-community&search=$encoded&sort=downloads&direction=-1&limit=$limit"
            } else {
                "https://huggingface.co/api/models?filter=gguf&search=$encoded&sort=downloads&direction=-1&limit=$limit"
            }
            val req = Request.Builder().url(url).apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("Hugging Face API HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<List<HfModelSummary>>(body)
            }
        }
    }

    suspend fun listModelFiles(
        repo: HfModelSummary,
        device: DeviceResources,
        contextLen: Int,
        token: String?
    ): Result<List<HfModelFileCandidate>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = "https://huggingface.co/api/models/${repo.id}/tree/main"
            val req = Request.Builder().url(url).apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
            }.build()
            val entries = client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) error("HF repo tree HTTP ${resp.code}")
                val body = resp.body?.string().orEmpty()
                json.decodeFromString<List<HfTreeEntry>>(body)
            }
            entries.filter { entry ->
                entry.type == "file" &&
                    entry.effectiveSizeBytes in 1L..MAX_MOBILE_FILE_BYTES &&
                    (entry.path.endsWith(".gguf", ignoreCase = true) ||
                        entry.path.endsWith(".task", ignoreCase = true) ||
                        entry.path.endsWith(".litertlm", ignoreCase = true))
            }.map { entry ->
                val format = if (entry.path.endsWith(".gguf", ignoreCase = true)) {
                    ModelFormat.GGUF
                } else {
                    ModelFormat.LITERT
                }
                val sizeBytes = entry.effectiveSizeBytes
                val quant = if (format == ModelFormat.GGUF) {
                    GgufMetadataParser.inferQuantFromName(entry.path)
                } else {
                    "INT4/LiteRT"
                }
                val paramsB = GgufMetadataParser.inferParamsB("${repo.id} ${entry.path}", sizeBytes)
                val estRamMb = DeviceFitCalculator.estimateRuntimeRamMb(sizeBytes, contextLen, paramsB)
                val fit = DeviceFitCalculator.evaluateFit(sizeBytes, estRamMb, device)
                HfModelFileCandidate(
                    repoId = repo.id,
                    fileName = entry.path,
                    sizeBytes = sizeBytes,
                    sha256 = entry.lfs?.oid?.takeIf { it.length == 64 },
                    format = format,
                    quantization = quant,
                    paramCountB = paramsB,
                    license = repo.license,
                    gated = repo.isGated,
                    downloadUrl = "https://huggingface.co/${repo.id}/resolve/main/${entry.path}",
                    estRamMb = estRamMb,
                    fitStatus = fit
                )
            }.sortedBy { it.fitStatus.ordinal }
        }
    }

    /**
     * Resumable HTTP range download with progress callback and optional SHA-256 verification.
     */
    suspend fun downloadFile(
        url: String,
        destination: File,
        expectedSha256: String?,
        token: String?,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val partFile = File(destination.parentFile, "${destination.name}.part")
            destination.parentFile?.mkdirs()
            val existingBytes = if (partFile.exists()) partFile.length() else 0L

            val req = Request.Builder().url(url).apply {
                if (!token.isNullOrBlank()) header("Authorization", "Bearer $token")
                if (existingBytes > 0L) header("Range", "bytes=$existingBytes-")
            }.build()

            client.newCall(req).execute().use { resp ->
                if (resp.code == 401 || resp.code == 403) {
                    error("Access denied (HTTP ${resp.code}). This model may be gated—add your Hugging Face token in Settings.")
                }
                if (!resp.isSuccessful && resp.code != 206) {
                    error("Download failed with HTTP ${resp.code}")
                }
                val append = resp.code == 206 && existingBytes > 0L
                val body = resp.body ?: error("Empty response body")
                val totalBytes = if (append) existingBytes + body.contentLength() else body.contentLength()
                var downloaded = if (append) existingBytes else 0L

                FileOutputStream(partFile, append).use { out ->
                    val buffer = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            out.write(buffer, 0, read)
                            downloaded += read
                            onProgress(downloaded, totalBytes)
                        }
                    }
                }
                if (destination.exists()) destination.delete()
                if (!partFile.renameTo(destination)) {
                    partFile.copyTo(destination, overwrite = true)
                    partFile.delete()
                }
            }

            val actualSha = computeSha256(destination)
            if (!expectedSha256.isNullOrBlank() && !actualSha.equals(expectedSha256, ignoreCase = true)) {
                destination.delete()
                error("SHA-256 mismatch: expected $expectedSha256, got $actualSha")
            }
            actualSha
        }
    }

    fun computeSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
