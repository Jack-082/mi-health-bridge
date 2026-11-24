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
        val outcome = SyncEngine(applicationContext).run(full = inputData.getBoolean(KEY_FULL, false))
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
        const val KEY_FULL = "full"
    }
}

object SyncScheduler {
    private const val PERIODIC = "mi_sync_periodic"
    private const val FAST = "mi_sync_fast"
    private const val NOW = "mi_sync_now"

    /** 手动触发的同步任务带此标签，界面据此显示“排队中/同步中”。 */
    const val TAG_MANUAL = "manual"
    const val TAG_FULL = "full"
    val UNIQUE_NAMES = listOf(PERIODIC, FAST, NOW)

    private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 15 分钟周期任务，作为兜底始终开启。 */
    fun ensurePeriodic(context: Context) {
        val req = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    /**
     * 立即同步。[full] = true 时忽略上次同步时间，按“回溯天数”重新拉取并覆盖。
     * 全量任务会替换排队中的手动任务；普通同步不会顶掉已在排队的全量任务。
     */
    fun syncNow(context: Context, full: Boolean = false) {
        val req = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf(SyncWorker.KEY_FULL to full))
            .addTag(TAG_MANUAL)
            .apply { if (full) addTag(TAG_FULL) }
            .build()
        val policy = if (full) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP
        WorkManager.getInstance(context).enqueueUniqueWork(NOW, policy, req)
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
