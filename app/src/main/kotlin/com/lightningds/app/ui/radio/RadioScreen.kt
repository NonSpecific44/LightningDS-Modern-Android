package com.lightningds.app.ui.radio

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lightningds.app.R
import com.lightningds.app.ui.components.Artwork
import com.lightningds.app.ui.components.EmptyState
import com.lightningds.app.ui.components.LoadContent
import com.lightningds.app.ui.components.LocalController
import com.lightningds.app.ui.components.LocalNavigator
import com.lightningds.app.ui.components.collectAsStateCompat
import com.lightningds.app.ui.components.rememberLoad
import com.lightningds.core.radio.RadioCategory
import com.lightningds.core.radio.RadioStation

object RadioRoutes {
    fun genre(tag: String) = "radio/genre/${Uri.encode(tag)}"
    fun country(code: String, name: String) = "radio/country/${Uri.encode(code)}/${Uri.encode(name)}"
}

@Composable
fun RadioScreen() {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val tabs = listOf(
        R.string.most_recent to Icons.Default.History,
        R.string.my_favorites to Icons.Default.Star,
        R.string.popular to Icons.Default.TrendingUp,
        R.string.all_genres to Icons.Default.Category,
        R.string.location to Icons.Default.Public,
        R.string.search_stations to Icons.Default.Search,
    )
    val c = LocalController.current
    Column(Modifier.fillMaxSize()) {
        PrimaryScrollableTabRow(selectedTabIndex = tab, edgePadding = 8.dp) {
            tabs.forEachIndexed { i, (label, icon) ->
                Tab(selected = i == tab, onClick = { tab = i }, text = { Text(stringResource(label)) }, icon = { Icon(icon, null) })
            }
        }
        when (tab) {
            0 -> {
                val recent by c.radioStore.recent.collectAsStateCompat()
                if (recent.isEmpty()) EmptyState(Icons.Default.History, stringResource(R.string.no_recent_stations)) else StationList(recent)
            }
            1 -> {
                val fav by c.radioStore.favorites.collectAsStateCompat()
                if (fav.isEmpty()) EmptyState(Icons.Default.Star, stringResource(R.string.no_favorite_stations)) else StationList(fav)
            }
            2 -> {
                val loader = rememberLoad("popular") { c.radio.popular(100) }
                LoadContent(loader) { StationList(it) }
            }
            3 -> {
                val nav = LocalNavigator.current
                val loader = rememberLoad("genres") { c.radio.genres() }
                LoadContent(loader) { CategoryList(it, Icons.Default.Category) { g -> nav.navigate(RadioRoutes.genre(g.code)) } }
            }
            4 -> {
                val nav = LocalNavigator.current
                val loader = rememberLoad("countries") { c.radio.countries() }
                LoadContent(loader) { CategoryList(it, Icons.Default.Public) { g -> nav.navigate(RadioRoutes.country(g.code, g.name)) } }
            }
            else -> RadioSearch()
        }
    }
}

@Composable
private fun CategoryList(items: List<RadioCategory>, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: (RadioCategory) -> Unit) {
    LazyColumn {
        items(items) { g ->
            ListItem(
                modifier = Modifier.clickable { onClick(g) },
                leadingContent = { Icon(icon, null) },
                headlineContent = { Text(g.name.replaceFirstChar { it.titlecase() }) },
                supportingContent = { Text(g.count.toString()) },
            )
        }
    }
}

@Composable
private fun RadioSearch() {
    val c = LocalController.current
    var query by rememberSaveable { mutableStateOf("") }
    var submitted by rememberSaveable { mutableStateOf("") }
    Column {
        OutlinedTextField(
            query, { query = it },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            singleLine = true,
            leadingIcon = { Icon(Icons.Default.Search, null) },
            placeholder = { Text(stringResource(R.string.search_stations)) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
        )
        if (submitted.isNotBlank()) {
            val loader = rememberLoad(submitted) { c.radio.search(submitted) }
            LoadContent(loader) { if (it.isEmpty()) EmptyState(Icons.Default.Radio, stringResource(R.string.search_no_results)) else StationList(it) }
        }
    }
}

@Composable
fun RadioGenreScreen(tag: String) {
    val c = LocalController.current
    val loader = rememberLoad(tag) { c.radio.byGenre(tag) }
    LoadContent(loader) { StationList(it) }
}

@Composable
fun RadioCountryScreen(code: String) {
    val c = LocalController.current
    val loader = rememberLoad(code) { c.radio.byCountry(code) }
    LoadContent(loader) { StationList(it) }
}

@Composable
fun StationList(stations: List<RadioStation>) {
    LazyColumn(Modifier.fillMaxSize()) {
        items(stations) { StationRow(it) }
        item {
            Text(stringResource(R.string.radio_directory_note), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
fun StationRow(s: RadioStation) {
    val c = LocalController.current
    val favorites by c.radioStore.favorites.collectAsStateCompat()
    val fav = favorites.any { it.id == s.id }
    ListItem(
        modifier = Modifier.clickable { c.playRadio(s) },
        leadingContent = { Artwork(s.logo, size = 48.dp, placeholder = Icons.Default.Radio) },
        headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(s.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        trailingContent = {
            IconButton(onClick = { c.radioStore.toggleFavorite(s) }) {
                Icon(
                    if (fav) Icons.Default.Star else Icons.Default.StarBorder,
                    stringResource(if (fav) R.string.remove_from_favorites else R.string.add_to_favorites),
                    tint = if (fav) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
    )
}
