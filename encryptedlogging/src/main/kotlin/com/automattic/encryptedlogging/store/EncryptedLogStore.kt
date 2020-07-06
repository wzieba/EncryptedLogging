package com.automattic.encryptedlogging.store

import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode
import com.automattic.encryptedlogging.Dispatcher
import com.automattic.encryptedlogging.Payload
import com.automattic.encryptedlogging.action.EncryptedLogAction
import com.automattic.encryptedlogging.action.EncryptedLogAction.UPLOAD_LOG
import com.automattic.encryptedlogging.annotations.action.Action
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptedLog
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptedLogUploadState.FAILED
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptedLogUploadState.UPLOADING
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptionUtils
import com.automattic.encryptedlogging.model.encryptedlogging.LogEncrypter
import com.automattic.encryptedlogging.network.BaseRequest.BaseNetworkError
import com.automattic.encryptedlogging.network.rest.wpcom.encryptedlog.EncryptedLogRestClient
import com.automattic.encryptedlogging.network.rest.wpcom.encryptedlog.UploadEncryptedLogResult.LogUploadFailed
import com.automattic.encryptedlogging.network.rest.wpcom.encryptedlog.UploadEncryptedLogResult.LogUploaded
import com.automattic.encryptedlogging.persistence.EncryptedLogSqlUtils
import com.automattic.encryptedlogging.tools.CoroutineEngine
import org.wordpress.android.util.AppLog
import org.wordpress.android.util.AppLog.T.API
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

private const val MAX_FAIL_COUNT = 3

// TODO: Add EncryptedLogModel DB migration

@Singleton
class EncryptedLogStore @Inject constructor(
    private val encryptedLogRestClient: EncryptedLogRestClient,
    private val encryptedLogSqlUtils: EncryptedLogSqlUtils,
    private val coroutineEngine: CoroutineEngine,
    dispatcher: Dispatcher
) : Store(dispatcher) {
    private val keyPair = EncryptionUtils.sodium.cryptoBoxKeypair()

    override fun onRegister() {
        AppLog.d(API, this.javaClass.name + ": onRegister")
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    override fun onAction(action: Action<*>) {
        val actionType = action.type as? EncryptedLogAction ?: return
        when (actionType) {
            UPLOAD_LOG -> {
                coroutineEngine.launch(API, this, "EncryptedLogStore: On UPLOAD_LOG") {
                    queueLogForUpload(action.payload as UploadEncryptedLogPayload)
                }
            }
        }
    }

    /**
     * Document the logic for when uploads will happen:
     *
     * Uploads should be checked:
     * 1. After [queueLogForUpload]
     * 2. After [handleSuccessfulUpload]
     * 3. Sometimes after [handleFailedUpload]
     * 4. At application start
     * 5. After a timer - maybe due to [handleFailedUpload]
     */
    suspend fun queueLogForUpload(payload: UploadEncryptedLogPayload) {
        // If the log file doesn't exist, there is nothing we can do
        if (!payload.file.exists()) {
            return
        }
        val encryptedLog = EncryptedLog(
                uuid = payload.uuid,
                file = payload.file
        )
        encryptedLogSqlUtils.insertOrUpdateEncryptedLog(encryptedLog)
        uploadNext()
    }

    private suspend fun uploadNextWithBackOffTiming() {
        // TODO: Add a backoff timer
        uploadNext()
    }

    private suspend fun uploadNext() {
        if (encryptedLogSqlUtils.getNumberOfUploadingEncryptedLogs() > 0) {
            // We are already uploading another log file
            return
        }
        val (logsToUpload, logsToDelete) = encryptedLogSqlUtils.getEncryptedLogsForUpload()
                .partition { it.file.exists() }
        // Delete any queued encrypted log records if the log file no longer exists
        encryptedLogSqlUtils.deleteEncryptedLogs(logsToDelete)
        // We want to upload a single file at a time
        logsToUpload.firstOrNull()?.let {
            uploadEncryptedLog(it)
        }
    }

    private suspend fun uploadEncryptedLog(encryptedLog: EncryptedLog) {
        // Update the upload state of the log
        encryptedLog.copy(uploadState = UPLOADING).let {
            encryptedLogSqlUtils.insertOrUpdateEncryptedLog(it)
        }
        val contents = LogEncrypter(
                sourceFile = encryptedLog.file,
                uuid = encryptedLog.uuid,
                publicKey = keyPair.publicKey
        ).encrypt()
        when (val result = encryptedLogRestClient.uploadLog(encryptedLog.uuid, contents)) {
            is LogUploaded -> handleSuccessfulUpload(encryptedLog)
            is LogUploadFailed -> handleFailedUpload(encryptedLog, result.error)
        }
    }

    private suspend fun handleSuccessfulUpload(encryptedLog: EncryptedLog) {
        deleteEncryptedLog(encryptedLog)
        emitChange(OnEncryptedLogUploaded(uuid = encryptedLog.uuid, file = encryptedLog.file))
        uploadNext()
    }

    private suspend fun handleFailedUpload(encryptedLog: EncryptedLog, error: UploadEncryptedLogError) {
        // TODO: Handle known server errors. If the upload failed for a temporary reason, don't increase failed count
        // TODO: and just queue it again with a back off timer
        val updatedEncryptedLog = encryptedLog.copy(
                failedCount = encryptedLog.failedCount + 1,
                uploadState = FAILED
        )
        if (updatedEncryptedLog.failedCount >= MAX_FAIL_COUNT) {
            // If a log failed to upload too many times, assume we can't upload it
            deleteEncryptedLog(updatedEncryptedLog)

            // Since we have a retry mechanism we should only notify that we failed to upload when we give up
            emitChange(OnEncryptedLogUploaded(uuid = encryptedLog.uuid, file = encryptedLog.file, error = error))
        } else {
            encryptedLogSqlUtils.insertOrUpdateEncryptedLog(updatedEncryptedLog)
        }
        uploadNextWithBackOffTiming()
    }

    private fun deleteEncryptedLog(encryptedLog: EncryptedLog) {
        // TODO: Do we want to delete the unencrypted log file?
        encryptedLogSqlUtils.deleteEncryptedLogs(listOf(encryptedLog))
    }

    class UploadEncryptedLogPayload(
        val uuid: String,
        val file: File
    ) : Payload<BaseNetworkError>()

    class OnEncryptedLogUploaded(
        val uuid: String,
        val file: File,
        error: UploadEncryptedLogError? = null
    ) : Store.OnChanged<UploadEncryptedLogError>() {
        init {
            this.error = error
        }
    }

    sealed class UploadEncryptedLogError : OnChangedError {
        object Unknown : UploadEncryptedLogError()
        class InvalidUuid(val message: String) : UploadEncryptedLogError()
    }
}
