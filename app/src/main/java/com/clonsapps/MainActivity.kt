package com.clonsapps

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.clonsapps.ui.AppRoot
import com.clonsapps.ui.theme.ClonAppsTheme
import com.clonsapps.work.Installer
import com.clonsapps.work.RunVerifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val app = ClonApp.get(this)
        setContent {
            val theme by app.settings.theme.collectAsState()
            ClonAppsTheme(theme) { AppRoot() }
        }
        // Layar konfirmasi pemasangan Android dibuka dari aktivitas yang sedang tampil.
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                Installer.confirmations.collect { intent -> runCatching { startActivity(intent) } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        val app = ClonApp.get(this)
        lifecycleScope.launch(Dispatchers.IO) { runCatching { RunVerifier.refresh(this@MainActivity) } }
        app.resumeQueueIfNeeded()
    }
}
