package com.lightningds.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lightningds.app.R
import com.lightningds.app.UpdateState
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.collectAsStateCompat

/** Shows update availability, download progress and errors from [com.lightningds.app.Updater]. */
@Composable
fun UpdateDialogHost() {
    val c = LocalController.current
    val context = LocalContext.current
    val state by c.updater.state.collectAsStateCompat()
    val s = state
    if (s is UpdateState.Idle || s is UpdateState.Checking) return
    val canInstall = context.packageManager.canRequestPackageInstalls()
    AlertDialog(
        onDismissRequest = { if (s !is UpdateState.Downloading && s !is UpdateState.Installing) c.updater.dismiss() },
        icon = { Icon(Icons.Default.SystemUpdate, null) },
        title = {
            Text(when (s) {
                is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Installing -> stringResource(R.string.update_available_title)
                UpdateState.UpToDate -> stringResource(R.string.update_up_to_date)
                is UpdateState.Failed -> stringResource(R.string.update_failed)
                else -> ""
            })
        },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                when (s) {
                    is UpdateState.Available -> {
                        Text(stringResource(R.string.update_available, s.update.version, c.updater.currentVersion))
                        if (s.update.notes.isNotBlank()) Text(s.update.notes, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        if (!canInstall) Text(stringResource(R.string.update_allow_install), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 8.dp))
                    }
                    is UpdateState.Downloading -> {
                        Text(stringResource(R.string.update_downloading, (s.progress * 100).toInt()))
                        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    }
                    is UpdateState.Installing -> Text(stringResource(R.string.update_installing))
                    UpdateState.UpToDate -> Text(stringResource(R.string.update_current, c.updater.currentVersion))
                    is UpdateState.Failed -> Text(s.message)
                    else -> Unit
                }
            }
        },
        confirmButton = {
            when (s) {
                is UpdateState.Available -> TextButton(onClick = {
                    if (!canInstall) {
                        runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))) }
                    } else {
                        c.launchIo { c.updater.downloadAndInstall(s.update) }
                    }
                }) { Text(stringResource(if (canInstall) R.string.update_install else R.string.update_allow)) }
                is UpdateState.Downloading, is UpdateState.Installing -> Unit
                else -> TextButton(onClick = { c.updater.dismiss() }) { Text(stringResource(R.string.ok)) }
            }
        },
        dismissButton = {
            if (s is UpdateState.Available) TextButton(onClick = { c.updater.dismiss() }) { Text(stringResource(R.string.update_later)) }
        },
    )
}
