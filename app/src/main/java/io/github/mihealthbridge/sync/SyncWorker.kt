package io.github.mihealthbridge.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val outcome = SyncEngine(applicationContext).run()
        // 快速模式：在当前任务之后追加下一次单次任务（WorkManager 周期任务最短 15 分钟）。
        // 快速链上的任务必须返回 success，否则追加的后继任务会连带失败、链条中断。
        if (inputData.getBoolean(KEY_FAST, false)) {
            SyncScheduler.scheduleNextFast(applicationContext)
            return Result.success()
        }
        return when (outcome) {
            is SyncOutcome.Failed -> if (runAttemptCount < 2) Result.retry() else Result.failure()
            else -> Result.success()
        }
    }

    companion object {
        const val KEY_FAST = "fast"
    }
}

object SyncScheduler {
    private const val PERIODIC = "mi_sync_periodic"
    private const val FAST = "mi_sync_fast"
    private const val NOW = "mi_sync_now"

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 15 分钟周期任务，作为兜底始终开启。 */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun syncNow(context: Context) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>().setConstraints(constraints).build()
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, ExistingWorkPolicy.REPLACE, req)
    }

    /** 开启/关闭快速模式（间隔分钟数，0 关闭）。 */
    fun setFastMode(context: Context, minutes: Int) {
        Settings(context).fastIntervalMinutes = minutes
        if (minutes <= 0) {
            WorkManager.getInstance(context).cancelUniqueWork(FAST)
        } else {
            enqueueFast(context, 0)
        }
    }

    /** App 启动时调用：快速模式已开启但任务链丢失时才重新排队。 */
    suspend fun ensureFast(context: Context) {
        if (Settings(context).fastIntervalMinutes <= 0) return
        val alive = WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(FAST).first()
            .any { !it.state.isFinished }
        if (!alive) enqueueFast(context, 0)
    }

    fun scheduleNextFast(context: Context) {
        val minutes = Settings(context).fastIntervalMinutes
        if (minutes > 0) enqueueFast(context, minutes.toLong(), ExistingWorkPolicy.APPEND_OR_REPLACE)
    }

    private fun enqueueFast(context: Context, delayMinutes: Long, policy: ExistingWorkPolicy = ExistingWorkPolicy.REPLACE) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInitialDelay(delayMinutes, TimeUnit.MINUTES)
            .setInputData(workDataOf(SyncWorker.KEY_FAST to true))
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(FAST, policy, req)
    }
}
