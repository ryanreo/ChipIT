package com.chipit.app.model

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.chipit.app.ChipITApplication
import com.chipit.app.data.db.ModelEntity
import com.chipit.app.data.db.ModelFormat
import java.io.File

class ModelDownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val repoId = inputData.getString(KEY_REPO_ID) ?: return Result.failure()
        val fileName = inputData.getString(KEY_FILE_NAME) ?: return Result.failure()
        val downloadUrl = inputData.getString(KEY_DOWNLOAD_URL) ?: return Result.failure()
        val sizeBytes = inputData.getLong(KEY_SIZE_BYTES, 0L)
        val expectedSha = inputData.getString(KEY_SHA256)
        val formatName = inputData.getString(KEY_FORMAT) ?: ModelFormat.GGUF.name
        val quantization = inputData.getString(KEY_QUANTIZATION) ?: "Q4_K_M"
        val paramCountB = inputData.getDouble(KEY_PARAM_COUNT_B, 0.0)
        val license = inputData.getString(KEY_LICENSE) ?: "See repo card"
        val estRamMb = inputData.getInt(KEY_EST_RAM_MB, 0)

        val app = applicationContext as ChipITApplication
        val container = app.container
        val modelsDir = File(applicationContext.filesDir, "models").apply { mkdirs() }
        val safeName = "${repoId.replace('/', '_')}_${fileName.substringAfterLast('/')}"
        val destFile = File(modelsDir, safeName)

        setForeground(createForegroundInfo(fileName, 0L, sizeBytes))
        setProgress(
            workDataOf(
                KEY_PROGRESS_FILE to fileName,
                KEY_PROGRESS_DOWNLOADED to 0L,
                KEY_PROGRESS_TOTAL to sizeBytes
            )
        )

        var lastNotifyMs = 0L
        val hfClient = HuggingFaceApiClient()
        val res = hfClient.downloadFile(
            url = downloadUrl,
            destination = destFile,
            expectedSha256 = expectedSha,
            token = container.settingsStore.hfToken,
            shouldCancel = { isStopped }
        ) { done, total ->
            val now = SystemClock.elapsedRealtime()
            val resolvedTotal = if (total > 0L) total else sizeBytes
            if (now - lastNotifyMs >= 500L || (resolvedTotal > 0L && done >= resolvedTotal)) {
                lastNotifyMs = now
                setProgressAsync(
                    workDataOf(
                        KEY_PROGRESS_FILE to fileName,
                        KEY_PROGRESS_DOWNLOADED to done,
                        KEY_PROGRESS_TOTAL to resolvedTotal
                    )
                )
                setForegroundAsync(createForegroundInfo(fileName, done, resolvedTotal))
            }
        }

        if (isStopped) {
            return Result.failure(workDataOf(KEY_ERROR to "Download cancelled"))
        }

        return res.fold(
            onSuccess = { actualSha ->
                val format = runCatching { ModelFormat.valueOf(formatName) }.getOrDefault(ModelFormat.GGUF)
                val gguf = if (format == ModelFormat.GGUF) GgufMetadataParser.parse(destFile) else null
                val entity = ModelEntity(
                    name = gguf?.modelName ?: repoId.substringAfterLast('/'),
                    repoId = repoId,
                    fileName = fileName,
                    sourceUrl = downloadUrl,
                    localPath = destFile.absolutePath,
                    format = format.name,
                    quantization = gguf?.quantization ?: quantization,
                    paramCountB = gguf?.paramCountB ?: paramCountB,
                    sizeBytes = destFile.length(),
                    sha256 = actualSha,
                    estRamMb = estRamMb,
                    license = license,
                    contextLimit = gguf?.contextLength ?: 2048
                )
                container.database.modelDao().insert(entity)
                Result.success(
                    workDataOf(
                        KEY_RESULT_NAME to entity.name,
                        KEY_RESULT_QUANT to entity.quantization
                    )
                )
            },
            onFailure = { err ->
                Result.failure(workDataOf(KEY_ERROR to (err.message ?: "Download failed")))
            }
        )
    }

    private fun createForegroundInfo(fileName: String, downloaded: Long, total: Long): ForegroundInfo {
        ensureChannel()
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val pct = if (total > 0L) ((downloaded * 100L) / total).toInt().coerceIn(0, 100) else 0
        val downloadedMb = downloaded / (1024L * 1024L)
        val totalMb = total / (1024L * 1024L)
        val contentText = if (totalMb > 0L) {
            "$downloadedMb / $totalMb MB ($pct%)"
        } else {
            "$downloadedMb MB downloaded"
        }

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("Downloading ${fileName.substringAfterLast('/')}")
            .setContentText(contentText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, total <= 0L)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Cancel", cancelIntent)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            )
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = applicationContext.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(
                        CHANNEL_ID,
                        "ChipIT Model Downloads",
                        NotificationManager.IMPORTANCE_LOW
                    )
                )
            }
        }
    }

    companion object {
        private const val CHANNEL_ID = "chipit_downloads"
        private const val NOTIFICATION_ID = 4202

        const val KEY_REPO_ID = "repoId"
        const val KEY_FILE_NAME = "fileName"
        const val KEY_DOWNLOAD_URL = "downloadUrl"
        const val KEY_SIZE_BYTES = "sizeBytes"
        const val KEY_SHA256 = "sha256"
        const val KEY_FORMAT = "format"
        const val KEY_QUANTIZATION = "quantization"
        const val KEY_PARAM_COUNT_B = "paramCountB"
        const val KEY_LICENSE = "license"
        const val KEY_EST_RAM_MB = "estRamMb"

        const val KEY_PROGRESS_FILE = "progressFile"
        const val KEY_PROGRESS_DOWNLOADED = "progressDownloaded"
        const val KEY_PROGRESS_TOTAL = "progressTotal"
        const val KEY_RESULT_NAME = "resultName"
        const val KEY_RESULT_QUANT = "resultQuant"
        const val KEY_ERROR = "error"
    }
}
