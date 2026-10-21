package com.srtstudio.app.service

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.ContextCompat
import com.srtstudio.app.data.FileUtils
import com.srtstudio.app.data.RenderRequest
import com.srtstudio.app.render.VideoRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed interface RenderState {
    data object Idle : RenderState
    data class Running(val stage: String, val progress: Float) : RenderState
    data class Done(val uri: Uri, val note: String? = null) : RenderState
    data class Failed(val message: String) : RenderState
}

/**
 * Process-wide handle on the (single) render job. The UI starts it here; [RenderService] keeps the
 * process alive and actually runs it, so rendering survives the app going to the background.
 */
object RenderController {
    private val _state = MutableStateFlow<RenderState>(RenderState.Idle)
    val state: StateFlow<RenderState> = _state.asStateFlow()

    @Volatile private var pending: RenderRequest? = null
    @Volatile private var job: Job? = null
    @Volatile private var cancelRequested = false

    val isRunning: Boolean get() = _state.value is RenderState.Running

    fun start(ctx: Context, request: RenderRequest) {
        if (isRunning) return
        pending = request
        cancelRequested = false
        _state.value = RenderState.Running("Preparing…", 0f)
        try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, RenderService::class.java))
        } catch (e: Throwable) {
            pending = null
            _state.value = RenderState.Failed("Couldn't start the render service: ${e.message}")
        }
    }

    fun cancel() {
        cancelRequested = true
        job?.cancel()
    }

    fun dismiss() {
        if (!isRunning) _state.value = RenderState.Idle
    }

    /** Called by the service once it is in the foreground. */
    internal fun launch(
        scope: CoroutineScope,
        ctx: Context,
        onProgress: (stage: String, progress: Float) -> Unit,
        onFinished: () -> Unit,
    ) {
        val request = pending
        if (request == null || job?.isActive == true) {
            onFinished()
            return
        }
        job = scope.launch {
            if (cancelRequested) {
                _state.value = RenderState.Idle
                pending = null
                job = null
                onFinished()
                return@launch
            }
            val tmp = File(ctx.cacheDir, "render_${System.currentTimeMillis()}.mp4")
            try {
                val renderer = VideoRenderer(
                    ctx, request, tmp,
                    ensureActive = { ensureActive() },
                    onProgress = { stage, p ->
                        _state.value = RenderState.Running(stage, p)
                        onProgress(stage, p)
                    },
                )
                renderer.run()

                _state.value = RenderState.Running("Saving to Movies…", 1f)
                onProgress("Saving to Movies…", 1f)
                _state.value = RenderState.Done(FileUtils.saveToMovies(ctx, tmp), renderer.report)
            } catch (e: CancellationException) {
                _state.value = RenderState.Idle
                throw e
            } catch (e: Throwable) {
                _state.value = RenderState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                tmp.delete()
                pending = null
                job = null
                onFinished()
            }
        }
    }
}
