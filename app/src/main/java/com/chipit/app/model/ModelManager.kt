package com.chipit.app.model

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import com.chipit.app.data.db.ModelDao
import com.chipit.app.data.db.ModelEntity
import com.chipit.app.data.db.ModelFormat
import com.chipit.app.data.prefs.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

data class DownloadProgress(
    val fileName: String,
    val downloadedBytes: Long,
    val totalBytes: Long
) {
    val fraction: Float
        get() = if (totalBytes > 0) (downloadedBytes.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f) else 0f
}

class ModelManager(
    private val context: Context,
    private val modelDao: ModelDao,
    private val settingsStore: SettingsStore,
    private val hfClient: HuggingFaceApiClient = HuggingFaceApiClient()
) {
    private val modelsDir: File
        get() = File(context.filesDir, "models").apply { mkdirs() }

    private val _downloadProgress = MutableStateFlow<DownloadProgress?>(null)
    val downloadProgress: StateFlow<DownloadProgress?> = _downloadProgress.asStateFlow()

    fun observeModels(): Flow<List<ModelEntity>> = modelDao.observeAll()

    fun getDeviceResources(): DeviceResources {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val statFs = StatFs(context.filesDir.absolutePath)
        val freeStorageMb = statFs.availableBytes / (1024L * 1024L)
        return DeviceResources(
            totalRamMb = (memInfo.totalMem / (1024L * 1024L)).toInt(),
            availableRamMb = (memInfo.availMem / (1024L * 1024L)).toInt(),
            freeStorageMb = freeStorageMb
        )
    }

    suspend fun searchHuggingFace(query: String, includeLiteRt: Boolean): Result<List<HfModelSummary>> {
        return hfClient.searchMobileModels(query, includeLiteRt, settingsStore.hfToken)
    }

    suspend fun listRepoFiles(repo: HfModelSummary, contextLen: Int): Result<List<HfModelFileCandidate>> {
        return hfClient.listModelFiles(repo, getDeviceResources(), contextLen, settingsStore.hfToken)
    }

    suspend fun downloadCandidate(candidate: HfModelFileCandidate): Result<ModelEntity> {
        val safeName = "${candidate.repoId.replace('/', '_')}_${candidate.fileName.substringAfterLast('/')}"
        val destFile = File(modelsDir, safeName)

        _downloadProgress.value = DownloadProgress(candidate.fileName, 0L, candidate.sizeBytes)
        val result = hfClient.downloadFile(
            url = candidate.downloadUrl,
            destination = destFile,
            expectedSha256 = candidate.sha256,
            token = settingsStore.hfToken
        ) { done, total ->
            _downloadProgress.value = DownloadProgress(candidate.fileName, done, total)
        }
        _downloadProgress.value = null

        return result.mapCatching { actualSha ->
            val gguf = if (candidate.format == ModelFormat.GGUF) {
                GgufMetadataParser.parse(destFile)
            } else null

            val entity = ModelEntity(
                name = gguf?.modelName ?: candidate.repoId.substringAfterLast('/'),
                repoId = candidate.repoId,
                fileName = candidate.fileName,
                sourceUrl = candidate.downloadUrl,
                localPath = destFile.absolutePath,
                format = candidate.format.name,
                quantization = gguf?.quantization ?: candidate.quantization,
                paramCountB = gguf?.paramCountB ?: candidate.paramCountB,
                sizeBytes = destFile.length(),
                sha256 = actualSha,
                estRamMb = candidate.estRamMb,
                license = candidate.license,
                contextLimit = gguf?.contextLength ?: 2048
            )
            val id = modelDao.insert(entity)
            entity.copy(id = id)
        }
    }

    suspend fun importLocalUri(uri: Uri): Result<ModelEntity> = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = context.contentResolver
            val displayName = resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            } ?: "imported_model.gguf"

            val destFile = File(modelsDir, displayName)
            resolver.openInputStream(uri)?.use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output, bufferSize = 64 * 1024)
                }
            } ?: error("Could not read selected file")

            val format = if (displayName.endsWith(".task", true) || displayName.endsWith(".litertlm", true)) {
                ModelFormat.LITERT
            } else {
                ModelFormat.GGUF
            }
            val gguf = if (format == ModelFormat.GGUF) GgufMetadataParser.parse(destFile) else null
            val sha = hfClient.computeSha256(destFile)
            val paramsB = gguf?.paramCountB ?: GgufMetadataParser.inferParamsB(displayName, destFile.length())
            val estRamMb = DeviceFitCalculator.estimateRuntimeRamMb(destFile.length(), 2048, paramsB)

            val entity = ModelEntity(
                name = gguf?.modelName ?: displayName.substringBeforeLast('.'),
                repoId = "local",
                fileName = displayName,
                sourceUrl = uri.toString(),
                localPath = destFile.absolutePath,
                format = format.name,
                quantization = gguf?.quantization ?: "INT4/LiteRT",
                paramCountB = paramsB,
                sizeBytes = destFile.length(),
                sha256 = sha,
                estRamMb = estRamMb,
                license = "Local file",
                contextLimit = gguf?.contextLength ?: 2048
            )
            val id = modelDao.insert(entity)
            entity.copy(id = id)
        }
    }

    suspend fun deleteModel(model: ModelEntity) = withContext(Dispatchers.IO) {
        runCatching { File(model.localPath).delete() }
        modelDao.delete(model)
    }
}
