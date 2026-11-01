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
                Thread.sleep(if (params.jobId == ID_TRIGGER) 4000L else 0L) // let the phone finish writing the file
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
