package com.clonsapps.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.clonsapps.ClonApp
import com.clonsapps.data.BuildState
import com.clonsapps.work.CloneService
import kotlinx.coroutines.launch

enum class Tab(val title: String, val icon: ImageVector) {
    CREATE("Buat", Icons.Filled.Add),
    QUEUE("Antrean", Icons.Filled.PlayArrow),
    HISTORY("Riwayat", Icons.Filled.DateRange),
    SETTINGS("Pengaturan", Icons.Filled.Settings),
}

@Composable
fun AppRoot() {
    val context = LocalContext.current
    val app = ClonApp.get(context)
    val w = app.wizard
    val jobs by app.jobs.jobs.collectAsState()
    var tab by rememberSaveable { mutableStateOf(Tab.CREATE) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val snack: (String) -> Unit = { m -> scope.launch { snackHost.showSnackbar(m) } }
    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    val onQueued: (String) -> Unit = { id ->
        CloneService.start(context)
        if (Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        selected = id
        tab = Tab.QUEUE
        snack("Ditambahkan ke antrean.")
    }

    val active = jobs.count { it.isActive }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 840.dp
        val medium = maxWidth >= 600.dp
        Scaffold(
            snackbarHost = { SnackbarHost(snackHost) },
            bottomBar = {
                if (!medium) {
                    NavigationBar {
                        Tab.values().forEach { t ->
                            NavigationBarItem(
                                selected = tab == t,
                                onClick = { tab = t; if (t != Tab.QUEUE && t != Tab.HISTORY) selected = null },
                                icon = { TabIcon(t, active) },
                                label = { Text(t.title) },
                            )
                        }
                    }
                }
            },
        ) { pad ->
            Row(Modifier.fillMaxSize().padding(pad)) {
                if (medium) {
                    NavigationRail(header = { Logo(36) }) {
                        Tab.values().forEach { t ->
                            NavigationRailItem(
                                selected = tab == t,
                                onClick = { tab = t },
                                icon = { TabIcon(t, active) },
                                label = { Text(t.title) },
                            )
                        }
                    }
                    VerticalDivider()
                }
                Column(Modifier.fillMaxSize()) {
                    Header(tab, w, selected, showLogo = !medium, onBack = { selected = null })
                    when (tab) {
                        Tab.CREATE -> CreateTab(w, onQueued)
                        Tab.QUEUE, Tab.HISTORY -> {
                            val list = if (tab == Tab.QUEUE) {
                                jobs.filter { it.isActive || (it.build == BuildState.BUILT && it.install != com.clonsapps.data.InstallState.INSTALLED) }
                                    .sortedBy { it.createdAt }
                            } else jobs.sortedByDescending { it.createdAt }
                            val empty = if (tab == Tab.QUEUE) "Antrean kosong. Buat clone dari tab Buat." else "Belum ada riwayat."
                            val sel = selected?.takeIf { id -> jobs.any { it.id == id } }
                            if (wide) {
                                Row(Modifier.fillMaxSize()) {
                                    JobList(list, sel, empty, { selected = it }, Modifier.widthIn(max = 420.dp).fillMaxHeight())
                                    VerticalDivider()
                                    if (sel != null) JobDetail(sel, snack) { selected = null }
                                    else Column(Modifier.fillMaxSize().padding(24.dp)) { Text("Pilih job untuk melihat detail.") }
                                }
                            } else if (sel != null) {
                                BackHandler { selected = null }
                                JobDetail(sel, snack) { selected = null }
                            } else {
                                JobList(list, null, empty, { selected = it })
                            }
                        }
                        Tab.SETTINGS -> SettingsScreen(snack)
                    }
                }
            }
        }
    }
}

@Composable
private fun TabIcon(t: Tab, active: Int) {
    if (t == Tab.QUEUE && active > 0) {
        BadgedBox(badge = { Badge { Text("$active") } }) { Icon(t.icon, contentDescription = t.title) }
    } else {
        Icon(t.icon, contentDescription = t.title)
    }
}

@Composable
private fun Header(tab: Tab, w: WizardState, selected: String?, showLogo: Boolean, onBack: () -> Unit) {
    Column {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val canBack = (tab == Tab.QUEUE || tab == Tab.HISTORY) && selected != null ||
                (tab == Tab.CREATE && w.step != WizardStep.SOURCE)
            if (canBack) {
                IconButton(onClick = {
                    if (tab == Tab.CREATE) {
                        w.step = if (w.step == WizardStep.REVIEW) WizardStep.CONFIGURE else WizardStep.SOURCE
                    } else onBack()
                }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Kembali") }
            } else if (showLogo) {
                Logo(32)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    when (tab) {
                        Tab.CREATE -> w.step.title
                        Tab.QUEUE -> "Antrean & hasil"
                        Tab.HISTORY -> "Riwayat"
                        Tab.SETTINGS -> "Pengaturan"
                    },
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                )
                if (tab == Tab.CREATE) Text("ClonApps · clone APK di perangkat", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (tab == Tab.CREATE) StepHeader(w.step)
        HorizontalDivider()
    }
}

@Composable
private fun CreateTab(w: WizardState, onQueued: (String) -> Unit) {
    if (w.step != WizardStep.SOURCE) BackHandler {
        w.step = if (w.step == WizardStep.REVIEW) WizardStep.CONFIGURE else WizardStep.SOURCE
    }
    when (w.step) {
        WizardStep.SOURCE -> SourceScreen(w)
        WizardStep.CONFIGURE -> ConfigureScreen(w)
        WizardStep.REVIEW -> ReviewScreen(w, onQueued)
    }
}
