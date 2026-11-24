package com.clonsapps.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.clonsapps.R
import com.clonsapps.engine.Issue
import com.clonsapps.ui.theme.Success
import com.clonsapps.ui.theme.Warning

@Composable
fun AppIcon(bitmap: Bitmap?, size: Int = 44) {
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)),
        )
    } else {
        Box(
            Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)).background(MaterialTheme.colorScheme.surfaceVariant)
        )
    }
}

@Composable
fun Logo(size: Int = 40) {
    Image(
        painter = painterResource(R.drawable.clonapps_logo),
        contentDescription = "ClonApps",
        modifier = Modifier.size(size.dp).clip(RoundedCornerShape((size / 4).dp)),
    )
}

@Composable
fun SectionCard(title: String? = null, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

enum class Tone { OK, WARN, ERROR, INFO, NEUTRAL }

@Composable
fun toneColor(t: Tone): Color = when (t) {
    Tone.OK -> Success
    Tone.WARN -> Warning
    Tone.ERROR -> MaterialTheme.colorScheme.error
    Tone.INFO -> MaterialTheme.colorScheme.primary
    Tone.NEUTRAL -> MaterialTheme.colorScheme.outline
}

@Composable
fun StatusPill(text: String, tone: Tone) {
    val c = toneColor(tone)
    Surface(color = c.copy(alpha = 0.14f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(c))
            Spacer(Modifier.width(6.dp))
            Text(text, color = c, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
fun KeyValue(key: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth()) {
        Text(key, Modifier.width(132.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            value,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
            fontSize = if (mono) 12.sp else MaterialTheme.typography.bodySmall.fontSize,
        )
    }
}

@Composable
fun IssueRow(issue: Issue) {
    val (icon, tone) = when (issue.severity) {
        Issue.Severity.ERROR -> Icons.Filled.Warning to Tone.ERROR
        Issue.Severity.WARNING -> Icons.Filled.Warning to Tone.WARN
        else -> Icons.Filled.Info to Tone.INFO
    }
    MessageRow(icon, tone, issue.title, issue.detail, issue.action)
}

@Composable
fun MessageRow(icon: ImageVector, tone: Tone, title: String, detail: String?, action: String?) {
    val c = toneColor(tone)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.copy(alpha = 0.08f)).padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = c, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
            if (!action.isNullOrBlank()) Text("Tindakan: $action", style = MaterialTheme.typography.bodySmall, color = c)
        }
    }
}

@Composable
fun OkRow(text: String) = MessageRow(Icons.Filled.CheckCircle, Tone.OK, text, null, null)

@Composable
fun ButtonRow(content: @Composable RowScope.() -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically, content = content)
}
