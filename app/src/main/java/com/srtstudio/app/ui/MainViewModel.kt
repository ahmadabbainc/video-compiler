package com.srtstudio.app.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.srtstudio.app.data.FileUtils
import com.srtstudio.app.data.Prefs
import com.srtstudio.app.data.RenderRequest
import com.srtstudio.app.data.RenderSettings
import com.srtstudio.app.data.SrtCue
import com.srtstudio.app.data.SrtParser
import com.srtstudio.app.render.FrameRenderer
import com.srtstudio.app.service.RenderController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min
import kotlin.math.roundToInt

data class ImageItem(val uri: Uri, val name: String)

data class UiState(
    val srtName: String? = null,
    val cues: List<SrtCue> = emptyList(),
    val srtError: String? = null,
    val images: List<ImageItem> = emptyList(),
    val imageNotice: String? = null,
    val audioUri: Uri? = null,
    val audioName: String? = null,
    val audioDurationMs: Long = 0L,
    val audioError: String? = null,
    val fontName: String? = null,
    val fontPath: String? = null,
    val fontError: String? = null,
    val settings: RenderSettings = RenderSettings(),
) {
    /** Length of the finished video: the audio if there is one, else the last caption plus a short tail. */
    val durationMs: Long
        get() = when {
            audioUri != null && audioDurationMs > 0 -> audioDurationMs
            cues.isNotEmpty() -> cues.maxOf { it.endMs } + 600L
            else -> 0L
        }
}

/** What the live preview depends on, so unrelated changes (e.g. the audio name) don't redraw it. */
private data class PreviewInput(
    val settings: RenderSettings,
    val firstCue: SrtCue?,
    val firstImage: Uri?,
    val fontPath: String?,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = Prefs(app)

    private val _ui = MutableStateFlow(UiState(settings = prefs.load(), fontPath = prefs.fontPath(), fontName = prefs.fontName()))
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    val render = RenderController.state

    private val _preview = MutableStateFlow<Bitmap?>(null)
    val preview: StateFlow<Bitmap?> = _preview.asStateFlow()

    @OptIn(FlowPreview::class)
    private val previewJob = viewModelScope.launch {
        _ui.map { PreviewInput(it.settings, it.cues.firstOrNull(), it.images.firstOrNull()?.uri, it.fontPath) }
            .distinctUntilChanged()
            .debounce(150)
            .collectLatest { input ->
                _preview.value = withContext(Dispatchers.Default) {
                    runCatching { renderPreview(input) }.getOrNull()
                }
            }
    }

    private fun renderPreview(input: PreviewInput): Bitmap {
        val s = input.settings
        val scale = 420f / min(s.size.width, s.size.height)
        val w = (s.size.width * scale).roundToInt() / 2 * 2
        val h = (s.size.height * scale).roundToInt() / 2 * 2

        val cue = input.firstCue ?: SrtCue(0L, 4000L, "Your captions will look like this")
        val font = input.fontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
        val renderer = FrameRenderer(
            getApplication(), s, w, h,
            listOfNotNull(input.firstImage), listOf(cue), cue.endMs + 1000L, font
        )
        renderer.draw(cue.startMs + (cue.endMs - cue.startMs) / 2)
        renderer.close()
        return renderer.bitmap
    }

    // ------------------------------------------------------------------ inputs

    fun onSrtPicked(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val name = FileUtils.displayName(app, uri)
                    name to SrtParser.parse(FileUtils.readText(app, uri))
                }
            }
            result.onSuccess { (name, cues) ->
                _ui.value = _ui.value.copy(
                    srtName = name,
                    cues = cues,
                    srtError = if (cues.isEmpty()) "No captions found. Is this a valid .srt file?" else null,
                )
            }.onFailure {
                _ui.value = _ui.value.copy(srtName = null, cues = emptyList(), srtError = it.message ?: "Couldn't read that file")
            }
        }
    }

    fun onImagesPicked(uris: List<Uri>) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val added = withContext(Dispatchers.IO) { uris.map { ImageItem(it, FileUtils.displayName(app, it)) } }
            addImages(added)
        }
    }

    fun onFolderPicked(tree: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val found = withContext(Dispatchers.IO) {
                runCatching { FileUtils.listImagesInTree(app, tree).map { ImageItem(it.first, it.second) } }
            }
            found.onSuccess { items ->
                if (items.isEmpty()) {
                    _ui.value = _ui.value.copy(imageNotice = "No images found directly inside that folder")
                } else {
                    addImages(items)
                }
            }.onFailure {
                _ui.value = _ui.value.copy(imageNotice = "Couldn't read that folder")
            }
        }
    }

    private fun addImages(added: List<ImageItem>) {
        val merged = (_ui.value.images + added)
            .distinctBy { it.uri }
            .sortedWith { a, b -> FileUtils.naturalOrder.compare(a.name, b.name) }
        _ui.value = _ui.value.copy(images = merged, imageNotice = null)
    }

    fun reverseImages() {
        _ui.value = _ui.value.copy(images = _ui.value.images.reversed())
    }

    fun shuffleImages() {
        _ui.value = _ui.value.copy(images = _ui.value.images.shuffled())
    }

    fun clearImages() {
        _ui.value = _ui.value.copy(images = emptyList())
    }

    fun onAudioPicked(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(app, uri)
                        val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                        check(dur > 0) { "Couldn't read that audio file" }
                        FileUtils.displayName(app, uri) to dur
                    } finally {
                        runCatching { retriever.release() }
                    }
                }
            }
            result.onSuccess { (name, dur) ->
                _ui.value = _ui.value.copy(audioUri = uri, audioName = name, audioDurationMs = dur, audioError = null)
            }.onFailure {
                _ui.value = _ui.value.copy(audioError = it.message ?: "Couldn't read that audio file")
            }
        }
    }

    fun clearAudio() {
        _ui.value = _ui.value.copy(audioUri = null, audioName = null, audioDurationMs = 0L, audioError = null)
    }

    fun onFontPicked(uri: Uri) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    app.filesDir.listFiles { f -> f.name.startsWith("font_") }?.forEach { it.delete() }
                    val dest = File(app.filesDir, "font_${System.currentTimeMillis()}")
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        dest.outputStream().use { input.copyTo(it) }
                    } ?: error("Couldn't open the font")
                    Typeface.createFromFile(dest) // throws if it isn't a usable font
                    FileUtils.displayName(app, uri) to dest.absolutePath
                }
            }
            result.onSuccess { (name, path) ->
                prefs.saveFont(path, name)
                _ui.value = _ui.value.copy(fontName = name, fontPath = path, fontError = null)
            }.onFailure {
                _ui.value = _ui.value.copy(fontError = "That file isn't a usable font")
            }
        }
    }

    fun clearFont() {
        prefs.saveFont(null, null)
        _ui.value = _ui.value.copy(fontName = null, fontPath = null, fontError = null)
    }

    fun updateSettings(change: (RenderSettings) -> RenderSettings) {
        val next = change(_ui.value.settings)
        prefs.save(next)
        _ui.value = _ui.value.copy(settings = next)
    }

    // ------------------------------------------------------------------ render

    fun generate() {
        val u = _ui.value
        if (u.cues.isEmpty()) return
        RenderController.start(
            getApplication(),
            RenderRequest(u.cues, u.images.map { it.uri }, u.audioUri, u.settings, u.fontPath)
        )
    }

    fun cancel() = RenderController.cancel()

    fun dismissResult() = RenderController.dismiss()
}
