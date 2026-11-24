package com.clonsapps

import android.app.Application
import android.content.Context
import com.clonsapps.data.BuildState
import com.clonsapps.data.JobRepository
import com.clonsapps.data.Settings
import com.clonsapps.platform.SigningKeyStore
import com.clonsapps.ui.WizardState
import com.clonsapps.work.CloneService
import java.io.File

class ClonApp : Application() {
    lateinit var jobs: JobRepository
        private set
    lateinit var keys: SigningKeyStore
        private set
    lateinit var settings: Settings
        private set
    lateinit var wizard: WizardState
        private set

    override fun onCreate() {
        super.onCreate()
        jobs = JobRepository(this)
        keys = SigningKeyStore(this)
        settings = Settings(this)
        wizard = WizardState(this)
        File(cacheDir, "picked").deleteRecursively()
    }

    /** Lanjutkan antrean yang tersisa (misalnya setelah proses aplikasi dimatikan sistem). */
    fun resumeQueueIfNeeded() {
        if (jobs.jobs.value.any { it.build == BuildState.QUEUED }) {
            runCatching { CloneService.start(this) }
        }
    }

    companion object {
        fun get(context: Context): ClonApp = context.applicationContext as ClonApp
    }
}
