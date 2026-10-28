package com.lightningds.app.ui.inputs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Airplay
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Input
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.lightningds.app.R
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.SectionHeader
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.core.renderer.RendererSource
import com.lightningds.core.renderer.RendererState

/** AirPlay and Songcast switches (and every other renderer input), like the original side bar. */
@Composable
fun InputsScreen() {
    val c = LocalController.current
    val state by c.rendererState.collectAsStateCompat()
    val s = state ?: return EmptyState(Icons.Default.Input, stringResource(R.string.no_device_selected))
    val airplay = s.sources.firstOrNull { it.isAirPlay }
    val songcast = s.sources.firstOrNull { it.isReceiver }
    LazyVerticalGrid(GridCells.Adaptive(320.dp), Modifier.fillMaxSize().padding(8.dp)) {
        item {
            InputCard(Icons.Default.Airplay, stringResource(R.string.airplay), stringResource(R.string.airplay_prompt, s.name), airplay, s)
        }
        item {
            InputCard(Icons.Default.Cast, stringResource(R.string.songcast), stringResource(R.string.songcast_prompt, s.name), songcast, s)
        }
        if (s.sources.isNotEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) { SectionHeader(stringResource(R.string.all_sources)) }
            items(s.sources.filter { it.visible }, key = { it.index }) { src ->
                OutlinedCard(onClick = { c.command { selectSource(src.index) } }, modifier = Modifier.padding(6.dp)) {
                    ListItem(
                        headlineContent = { Text(src.name) },
                        supportingContent = { Text(src.type) },
                        leadingContent = { Icon(Icons.Default.Input, null) },
                        trailingContent = { if (src.index == s.sourceIndex) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary) },
                    )
                }
            }
        }
    }
}

@Composable
private fun InputCard(icon: ImageVector, title: String, prompt: String, source: RendererSource?, s: RendererState) {
    val c = LocalController.current
    val active = source != null && s.sourceIndex == source.index
    ElevatedCard(Modifier.padding(6.dp).fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, Modifier.size(40.dp), tint = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 16.dp).weight(1f))
                Switch(
                    checked = active,
                    enabled = source != null,
                    onCheckedChange = { on ->
                        c.command {
                            if (on) selectSource(source!!.index)
                            else s.sources.firstOrNull { it.type == RendererSource.PLAYLIST }?.let { selectSource(it.index) }
                        }
                    },
                )
            }
            Spacer(Modifier.size(12.dp))
            Text(
                if (source == null) stringResource(R.string.input_not_available) else prompt,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(max = 520.dp),
            )
        }
    }
}
