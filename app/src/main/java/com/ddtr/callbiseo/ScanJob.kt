package com.ddtr.callbiseo

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.MediaStore

/**
 * Wakes up when a new audio file appears (= a call just ended and the phone saved the recording),
 * and every hour or so to retry anything that failed. Then summarises in the background.
 */
class ScanJob : JobService() {
    @Volatile private var worker: Thread? = null

    override fun onStartJob(params: JobParameters): Boolean {
        if (params.jobId == ID_TRIGGER) schedule(applicationContext) // content-trigger jobs fire once; arm again
        val ctx = applicationContext
        worker = Thread {
            try {
                if (params.jobId == ID_CALLEND) {
                    // 통화가 막 끝남: 녹음 파일이 저장될 때까지 2초마다 확인 (최대 90초)
                    val end = System.currentTimeMillis() + 90_000L
                    while (System.currentTimeMillis() < end) {
                        Thread.sleep(2000L)
                        if (Processor.sync(ctx) > 0) break
                    }
                } else Thread.sleep(if (params.jobId == ID_TRIGGER) 4000L else 0L) // let the phone finish writing the file
                Processor.sync(ctx)
                Processor.requeue(ctx)
                if (Prefs.auto(ctx) || params.jobId == ID_RETRY) Processor.kick(ctx).get()
            } catch (e: Exception) {
            } finally {
                jobFinished(params, false)
            }
        }.also { it.start() }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean = true

    companion object {
        const val ID_TRIGGER = 41
        const val ID_RETRY = 42
        const val ID_CALLEND = 43

        /** 통화가 끝난 순간: 기다리지 않고 바로 도는 급행 작업으로 녹음을 찾아 요약 */
        fun callEnded(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val b = JobInfo.Builder(ID_CALLEND, ComponentName(ctx, ScanJob::class.java))
                .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            if (android.os.Build.VERSION.SDK_INT >= 31) b.setExpedited(true) else b.setOverrideDeadline(0)
            try { js.schedule(b.build()) } catch (e: Exception) {
                try { js.schedule(JobInfo.Builder(ID_CALLEND, ComponentName(ctx, ScanJob::class.java)).setOverrideDeadline(0).build()) } catch (x: Exception) { }
            }
        }

        fun schedule(ctx: Context) {
            val js = ctx.getSystemService(JobScheduler::class.java) ?: return
            val cn = ComponentName(ctx, ScanJob::class.java)
            try {
                val trigger = JobInfo.Builder(ID_TRIGGER, cn)
                    .addTriggerContentUri(
                        JobInfo.TriggerContentUri(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                            JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS
                        )
                    )
                    .setTriggerContentUpdateDelay(3_000)
                    .setTriggerContentMaxDelay(60_000)
                    .build()
                js.schedule(trigger)
            } catch (e: Exception) { }
            if (js.getPendingJob(ID_RETRY) == null) {
                try {
                    val retry = JobInfo.Builder(ID_RETRY, cn)
                        .setPeriodic(60 * 60_000L)
                        .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
                        .setPersisted(true)
                        .build()
                    js.schedule(retry)
                } catch (e: Exception) { }
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ScanJob.schedule(context.applicationContext)
    }
}
