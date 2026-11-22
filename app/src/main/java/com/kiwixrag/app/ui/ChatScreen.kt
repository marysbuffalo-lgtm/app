package com.kiwixrag.app.ui

import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.kiwixrag.app.rag.Phase
import com.kiwixrag.app.storage.Accelerator

private val Good = Color(0xFF4FD1A5)
private val Warn = Color(0xFFFFC155)
private val Bad = Color(0xFFFF7A6B)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    vm: ChatViewModel,
    onPickModel: () -> Unit,
    onPickZimFolder: () -> Unit,
    onGrantAllFiles: () -> Unit,
) {
    val messages by vm.messages.collectAsState()
    val phase by vm.phase.collectAsState()
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    var input by rememberSaveable { mutableStateOf("") }
    val listState = rememberLazyListState()
    val busy = phase != Phase.Idle

    LaunchedEffect(messages.size, messages.lastOrNull()?.text?.length) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.lastIndex)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kiwix RAG") },
                actions = {
                    IconButton(onClick = { vm.clearChat() }, enabled = messages.isNotEmpty()) {
                        Icon(Icons.Filled.DeleteSweep, contentDescription = "Clear chat")
                    }
                    TextButton(onClick = { settingsOpen = !settingsOpen }) { Text(if (settingsOpen) "Hide setup" else "Setup") }
                },
            )
        },
        bottomBar = {
            Column(Modifier.background(MaterialTheme.colorScheme.surface)) {
                if (busy) {
                    Text(phase.label, Modifier.padding(horizontal = 16.dp, vertical = 4.dp), style = MaterialTheme.typography.labelMedium)
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                }
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = input,
                        onValueChange = { input = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("Ask your Kiwix library") },
                        maxLines = 4,
                        enabled = !busy,
                    )
                    if (busy) {
                        IconButton(onClick = { vm.stop() }) { Icon(Icons.Filled.Stop, contentDescription = "Stop") }
                    } else {
                        IconButton(onClick = { vm.ask(input); input = "" }, enabled = input.isNotBlank()) {
                            Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                        }
                    }
                }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            StatusBar(vm)
            if (settingsOpen) SetupPanel(vm, onPickModel, onPickZimFolder, onGrantAllFiles)
            if (messages.isEmpty()) {
                Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Everything runs on this phone. Pick a model and your folder of .zim files under Setup, then ask a question. " +
                            "Answers cite the exact ZIM archive, article and photo.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(messages, key = { it.id }) { m -> MessageItem(m, onOpen = vm::openArticle) }
                }
            }
        }
    }
}

@Composable
private fun StatusBar(vm: ChatViewModel) {
    val model by vm.model.collectAsState()
    val lib by vm.libState.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val (dot, text) = when (val m = model) {
                ModelState.None -> Bad to "No model loaded"
                is ModelState.Loading -> Warn to "Loading ${m.name}"
                is ModelState.Ready -> Good to "${m.name}  ${m.backend}  ${if (m.vision) "vision" else "text only"}"
                is ModelState.Failed -> Bad to "Model failed"
            }
            Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
            Text(text, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            val count = lib.archives.size
            Box(Modifier.size(10.dp).clip(CircleShape).background(if (count > 0) Good else Bad))
            Text(
                when {
                    lib.loading -> "Opening ZIM files"
                    count == 0 -> "No ZIM library"
                    else -> "$count ZIM${if (count == 1) "" else "s"}, ${Formatter.formatFileSize(context, lib.totalBytes)}"
                },
                style = MaterialTheme.typography.labelLarge,
            )
        }
        (model as? ModelState.Failed)?.let { Text(it.message, color = Bad, style = MaterialTheme.typography.bodySmall) }
        (model as? ModelState.Ready)?.let {
            if (it.viaDescriptor) Text("Model opened through a file descriptor (no copy).", style = MaterialTheme.typography.bodySmall)
        }
        lib.message?.let { Text(it, color = Warn, style = MaterialTheme.typography.bodySmall) }
    }
    HorizontalDivider()
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupPanel(vm: ChatViewModel, onPickModel: () -> Unit, onPickZim: () -> Unit, onGrantAllFiles: () -> Unit) {
    val accel by vm.accelerator.collectAsState()
    val top by vm.topArticles.collectAsState()
    val lib by vm.libState.collectAsState()
    var granted by remember { mutableStateOf(vm.hasAllFilesAccess()) }
    LaunchedEffect(Unit) { granted = vm.hasAllFilesAccess() }
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1. Model", fontWeight = FontWeight.SemiBold)
            Text("Pick a .task (MediaPipe) or .litertlm (LiteRT-LM) file already on the phone. It is opened in place, never copied.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onPickModel) { Text("Choose model file") }
            Text("Accelerator", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Accelerator.entries.forEach { a ->
                    FilterChip(selected = accel == a, onClick = { vm.setAccelerator(a) }, label = { Text(a.label) })
                }
            }
            HorizontalDivider()
            Text("2. ZIM library", fontWeight = FontWeight.SemiBold)
            if (!granted) {
                Text("All files access is needed to open 70 GB of ZIM files in place.", color = Warn, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = { onGrantAllFiles() }) { Text("Grant all files access") }
            } else {
                Text("All files access granted.", color = Good, style = MaterialTheme.typography.bodySmall)
            }
            OutlinedButton(onClick = onPickZim) { Text("Choose ZIM folder") }
            lib.archives.forEach { z ->
                Text("${z.fileName}  ${if (z.hasFulltext) "full-text" else "titles only"}", style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
            Text("Articles to retrieve: $top", style = MaterialTheme.typography.labelLarge)
            Slider(value = top.toFloat(), onValueChange = { vm.setTopArticles(it.toInt()) }, valueRange = 3f..5f, steps = 1)
        }
    }
}

@Composable
private fun MessageItem(m: ChatMessage, onOpen: (SourceUi) -> Unit) {
    if (m.fromUser) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.widthIn(max = 320.dp)) {
                Text(m.text, Modifier.padding(12.dp))
            }
        }
        return
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
            SelectionContainer {
                Text(
                    m.text.ifEmpty { if (m.streaming) "..." else "" },
                    Modifier.padding(12.dp).fillMaxWidth(),
                    color = if (m.isError) Bad else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        if (m.sources.isNotEmpty()) SourcesCard(m.sources, onOpen)
    }
}

@Composable
private fun SourcesCard(sources: List<SourceUi>, onOpen: (SourceUi) -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val photoCount = sources.sumOf { it.photos.size }
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "Sources & Retrieved Photos  (${sources.size} pages, $photoCount photos)",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = if (expanded) "Collapse" else "Expand")
                }
            }
            if (expanded) {
                sources.forEach { s ->
                    HorizontalDivider()
                    Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("[S${s.index}] ${s.title}", fontWeight = FontWeight.SemiBold)
                        Text("ZIM: ${s.zimFileName}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Path: ${s.path}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (s.snippet.isNotBlank()) Text(s.snippet.take(200), style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        if (s.photos.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                s.photos.forEach { p ->
                                    Column(Modifier.widthIn(max = 150.dp)) {
                                        p.thumbnail?.let {
                                            Image(
                                                bitmap = it,
                                                contentDescription = p.caption,
                                                modifier = Modifier.height(110.dp).clip(RoundedCornerShape(8.dp)),
                                                contentScale = ContentScale.Fit,
                                            )
                                        }
                                        Text("[${p.label}] ${p.fileName}", style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                        Text(p.caption, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        AssistChip(onClick = { onOpen(s) }, label = { Text("View full article") })
                    }
                }
            }
        }
    }
}
