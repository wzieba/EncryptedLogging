package com.automattic.encryptedlogging.persistence

import com.wellsql.generated.EncryptedLogModelTable
import com.yarolegovich.wellsql.SelectQuery
import com.yarolegovich.wellsql.WellSql
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptedLog
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptedLogModel
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptedLogUploadState.FAILED
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptedLogUploadState.QUEUED
import com.automattic.encryptedlogging.model.encryptedlogging.EncryptedLogUploadState.UPLOADING

internal class EncryptedLogSqlUtils {
    fun insertOrUpdateEncryptedLog(encryptedLog: EncryptedLog) {
        insertOrUpdateEncryptedLogs(listOf(encryptedLog))
    }

    fun insertOrUpdateEncryptedLogs(encryptedLogs: List<EncryptedLog>) {
        val encryptedLogModels = encryptedLogs.map { EncryptedLogModel.fromEncryptedLog(it) }
        // Since we have a unique constraint for uuid with 'on conflict replace', if there is an existing log,
        // it'll be replaced with the new one. No need to check if the log already exists.
        WellSql.insert(encryptedLogModels).execute()
    }

    fun getEncryptedLog(uuid: String): EncryptedLog? {
        return getEncryptedLogModel(uuid)?.let { EncryptedLog.fromEncryptedLogModel(it) }
    }

    fun getUploadingEncryptedLogs(): List<EncryptedLog> =
            getUploadingEncryptedLogsQuery().asModel.map { EncryptedLog.fromEncryptedLogModel(it) }

    fun getNumberOfUploadingEncryptedLogs(): Long = getUploadingEncryptedLogsQuery().count()

    fun deleteEncryptedLogs(encryptedLogList: List<EncryptedLog>) {
        if (encryptedLogList.isEmpty()) {
            return
        }
        WellSql.delete(EncryptedLogModel::class.java)
                .where()
                .isIn(EncryptedLogModelTable.UUID, encryptedLogList.map { it.uuid })
                .endWhere()
                .execute()
    }

    fun getEncryptedLogForUpload(): EncryptedLog? {
        val uploadStates = listOf(QUEUED, FAILED).map { it.value }
        return WellSql.select(EncryptedLogModel::class.java)
                .where()
                .isIn(EncryptedLogModelTable.UPLOAD_STATE_DB_VALUE, uploadStates)
                .endWhere()
                // Queued status should have priority over failed status
                .orderBy(EncryptedLogModelTable.UPLOAD_STATE_DB_VALUE, SelectQuery.ORDER_ASCENDING)
                // First log that's queued should have priority
                .orderBy(EncryptedLogModelTable.DATE_CREATED, SelectQuery.ORDER_ASCENDING)
                .limit(1)
                .asModel
                .map {
                    EncryptedLog.fromEncryptedLogModel(it)
                }
                .firstOrNull()
    }

    private fun getEncryptedLogModel(uuid: String): EncryptedLogModel? {
        return WellSql.select(EncryptedLogModel::class.java)
                .where()
                .equals(EncryptedLogModelTable.UUID, uuid)
                .endWhere()
                .asModel
                .firstOrNull()
    }

    private fun getUploadingEncryptedLogsQuery(): SelectQuery<EncryptedLogModel> {
        return WellSql.select(EncryptedLogModel::class.java)
            .where()
            .equals(EncryptedLogModelTable.UPLOAD_STATE_DB_VALUE, UPLOADING.value)
            .endWhere()
    }
}
