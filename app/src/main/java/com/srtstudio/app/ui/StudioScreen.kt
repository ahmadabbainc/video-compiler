package com.srtstudio.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.srtstudio.app.data.CaptionColors
import com.srtstudio.app.data.CaptionPosition
import com.srtstudio.app.data.CaptionStyle
import com.srtstudio.app.data.ImageMapping
import com.srtstudio.app.data.Palettes
import com.srtstudio.app.data.VideoSize
import com.srtstudio.app.render.BitmapLoader
import com.srtstudio.app.service.RenderState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

@Composable
fun StudioScreen(vm: MainViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val render by vm.render.collectAsStateWithLifecycle()
    val preview by vm.preview.collectAsStateWithLifecycle()
    val ctx = LocalContext.current

    val srtPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onSrtPicked(uri)
    }
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) vm.onImagesPicked(uris)
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) vm.onFolderPicked(uri)
    }
    var lookOpen by rememberSaveable { mutableStateOf(false) }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onAudioPicked(uri)
    }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.onFontPicked(uri)
    }
    // The render works either way; the permission only controls whether the progress notification shows.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        vm.generate()
    }

    fun onGenerate() {
        lookOpen = false
        val needsAsk = Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsAsk) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) else vm.generate()
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    when (val r = render) {
                        is RenderState.Running -> RunningPanel(r, onCancel = vm::cancel)
                        else -> GradientButton(
                            text = "Generate video",
                            icon = Icons.Rounded.Movie,
                            enabled = ui.cues.isNotEmpty(),
                            onClick = { onGenerate() },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Header()

            when (val r = render) {
                is RenderState.Done -> DoneCard(r.uri, onDismiss = vm::dismissResult)
                is RenderState.Failed -> FailedCard(r.message, onDismiss = vm::dismissResult)
                else -> Unit
            }

            PreviewCard(preview, ui.settings.size)

            // ---- 1. captions
            SectionCard(
                title = "Captions",
                icon = Icons.Rounded.Subtitles,
                trailing = {
                    FilledTonalButton(onClick = { srtPicker.launch(arrayOf("*/*")) }) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ui.srtName == null) "Choose .srt" else "Change")
                    }
                },
            ) {
                if (ui.srtName == null && ui.srtError == null) {
                    SmallLabel("Pick a subtitle file. Arabic, Hebrew and other right-to-left text is laid out correctly.")
                }
                ui.srtName?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                if (ui.cues.isNotEmpty()) {
                    SmallLabel("${ui.cues.size} captions · ${formatTime(ui.cues.last().endMs)} long")
                }
                ui.srtError?.let { ErrorText(it) }
            }

            // ---- 2. visuals
            SectionCard(
                title = "Images",
                icon = Icons.Rounded.Image,
                trailing = {
                    FilledTonalButton(onClick = { folderPicker.launch(null) }) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ui.images.isEmpty()) "Add images" else "Add more")
                    }
                },
            ) {
                TextButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                    Text("Select individual images instead")
                }
                ui.imageNotice?.let { ErrorText(it) }
                if (ui.images.isEmpty()) {
                    SmallLabel("Optional. Tap Add images and choose a folder: every image inside it is added. Without images you get a clean gradient background.")
                } else {
                    SmallLabel("${ui.images.size} images · sorted by file name (img2 before img10)")
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(ui.images, key = { it.uri }) { item -> Thumb(item.uri) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = vm::reverseImages) {
                            Icon(Icons.Rounded.SwapHoriz, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Reverse")
                        }
                        OutlinedButton(onClick = vm::shuffleImages) {
                            Icon(Icons.Rounded.Shuffle, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Shuffle")
                        }
                        TextButton(onClick = vm::clearImages) { Text("Clear") }
                    }
                    SmallLabel("Image order")
                    ChipRow(ImageMapping.entries, ui.settings.mapping, { it.label }) { m ->
                        vm.updateSettings { it.copy(mapping = m) }
                    }
                }
            }

            // ---- 3. audio
            SectionCard(
                title = "Audio",
                icon = Icons.Rounded.MusicNote,
                trailing = {
                    FilledTonalButton(onClick = { audioPicker.launch(arrayOf("audio/*")) }) {
                        Icon(Icons.Rounded.FolderOpen, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(if (ui.audioName == null) "Add audio" else "Change")
                    }
                },
            ) {
                if (ui.audioName == null && ui.audioError == null) {
                    SmallLabel("Optional. MP3, WAV, M4A, OGG and more. The video lasts as long as the audio.")
                }
                ui.audioName?.let {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(it, style = MaterialTheme.typography.bodyMedium)
                            SmallLabel(formatTime(ui.audioDurationMs))
                        }
                        IconButton(onClick = vm::clearAudio) { Icon(Icons.Rounded.Close, "Remove audio") }
                    }
                }
                ui.audioError?.let { ErrorText(it) }
            }

            // ---- 4. look
            SectionCard(
                title = "Look",
                icon = Icons.Rounded.Tune,
                onHeaderClick = { lookOpen = !lookOpen },
                trailing = {
                    Icon(
                        if (lookOpen) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                        contentDescription = if (lookOpen) "Hide look settings" else "Show look settings",
                    )
                },
            ) {
              if (!lookOpen) {
                SmallLabel(
                    "${ui.settings.size.label} · ${ui.settings.fps} fps · ${ui.settings.style.label} · " +
                        "${ui.settings.position.label} · tap to edit"
                )
              } else {
                SmallLabel("Format")
                ChipRow(VideoSize.entries, ui.settings.size, { it.label }) { v -> vm.updateSettings { it.copy(size = v) } }

                SmallLabel("Frame rate")
                ChipRow(listOf(24, 30, 60), ui.settings.fps, { "$it fps" }) { f -> vm.updateSettings { it.copy(fps = f) } }

                SmallLabel("Caption position")
                ChipRow(CaptionPosition.entries, ui.settings.position, { it.label }) { p -> vm.updateSettings { it.copy(position = p) } }

                SmallLabel("Caption style")
                ChipRow(CaptionStyle.entries, ui.settings.style, { it.label }) { st -> vm.updateSettings { it.copy(style = st) } }

                SmallLabel("Caption colour")
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CaptionColors.forEach { c ->
                        ColorSwatch(Color(c), selected = ui.settings.textColor == c) {
                            vm.updateSettings { it.copy(textColor = c) }
                        }
                    }
                }

                LabeledSlider(
                    label = "Caption size",
                    valueText = "${(ui.settings.fontScale * 1000).roundToInt() / 10f}%",
                    value = ui.settings.fontScale,
                    range = 0.03f..0.10f,
                ) { v -> vm.updateSettings { it.copy(fontScale = v) } }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.TextFields, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(ui.fontName ?: "Default font", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    if (ui.fontPath != null) TextButton(onClick = vm::clearFont) { Text("Reset") }
                    TextButton(onClick = { fontPicker.launch(arrayOf("*/*")) }) { Text("Custom font…") }
                }

                ui.fontError?.let { ErrorText(it) }

                SmallLabel("Background gradient")
                ChipRow(Palettes.indices.toList(), ui.settings.palette, { Palettes[it].name }) { p ->
                    vm.updateSettings { it.copy(palette = p) }
                }

                if (ui.images.isNotEmpty()) {
                    SwitchRow("Slow zoom and pan on images (Ken Burns)", ui.settings.kenBurns) { on ->
                        vm.updateSettings { it.copy(kenBurns = on) }
                    }
                    LabeledSlider(
                        label = "Crossfade between images",
                        valueText = if (ui.settings.crossfadeMs == 0) "Off" else "${ui.settings.crossfadeMs} ms",
                        value = ui.settings.crossfadeMs.toFloat(),
                        range = 0f..1500f,
                    ) { v -> vm.updateSettings { it.copy(crossfadeMs = (v / 50f).roundToInt() * 50) } }
                }
              }
            }

            // ---- summary
            if (ui.cues.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = Mint, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    SmallLabel("${formatTime(ui.durationMs)} · ${ui.settings.size.label} · ${ui.settings.fps} fps")
                }
            }
            Spacer(Modifier.size(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------- pieces

@Composable
private fun Header() {
    Column(Modifier.padding(top = 12.dp, bottom = 4.dp)) {
        Text(
            "SRT Studio",
            style = MaterialTheme.typography.headlineLarge.copy(
                brush = Brush.horizontalGradient(listOf(Violet, Mint))
            ),
        )
        Text(
            "Turn subtitles, images and audio into a finished video. Fully on-device.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PreviewCard(preview: Bitmap?, size: VideoSize) {
    Surface(
        shape = RoundedCornerShape(22.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(Modifier.padding(10.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val ratio = size.width.toFloat() / size.height
            Box(
                Modifier
                    .heightIn(max = 360.dp)
                    .aspectRatio(ratio)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (preview != null) {
                    Image(
                        bitmap = preview.asImageBitmap(),
                        contentDescription = "Live preview",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

@Composable
private fun Thumb(uri: Uri) {
    val ctx = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(null, uri) {
        value = withContext(Dispatchers.IO) { BitmapLoader.decode(ctx, uri, 160, 160)?.asImageBitmap() }
    }
    Box(
        Modifier
            .size(64.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        bitmap?.let {
            Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun ErrorText(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.ErrorOutline, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun RunningPanel(state: RenderState.Running, onCancel: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(state.stage, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
            Text("${(state.progress * 100).toInt()}%", style = MaterialTheme.typography.labelLarge)
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
        LinearProgressIndicator(
            progress = { state.progress },
            modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.primary,
        )
        SmallLabel("You can leave the app. Rendering continues in the background.")
    }
}

@Composable
private fun DoneCard(uri: Uri, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    SectionCard(
        title = "Your video is ready",
        icon = Icons.Rounded.CheckCircle,
        trailing = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss") } },
    ) {
        SmallLabel("Saved to Movies / SRT Studio")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            FilledTonalButton(onClick = {
                val view = Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(view) }
            }) {
                Icon(Icons.Rounded.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Play")
            }
            OutlinedButton(onClick = {
                val send = Intent(Intent.ACTION_SEND)
                    .setType("video/mp4")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                runCatching { ctx.startActivity(Intent.createChooser(send, "Share video")) }
            }) {
                Icon(Icons.Rounded.Share, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Share")
            }
        }
    }
}

@Composable
private fun FailedCard(message: String, onDismiss: () -> Unit) {
    SectionCard(
        title = "Render failed",
        icon = Icons.Rounded.ErrorOutline,
        trailing = { IconButton(onClick = onDismiss) { Icon(Icons.Rounded.Close, "Dismiss") } },
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun formatTime(ms: Long): String {
    val total = ms / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%02d:%02d".format(m, s)
}
