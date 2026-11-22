package com.kiwixrag.app.ui

import android.app.Application
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.kiwixrag.app.llm.EngineFactory
import com.kiwixrag.app.llm.LlmEngine
import com.kiwixrag.app.llm.PromptBuilder
import com.kiwixrag.app.rag.AnswerRunner
import com.kiwixrag.app.rag.Outcome
import com.kiwixrag.app.rag.Phase
import com.kiwixrag.app.rag.RagPipeline
import com.kiwixrag.app.rag.Retrieval
import com.kiwixrag.app.ragApp
import com.kiwixrag.app.storage.Accelerator
import com.kiwixrag.app.storage.ModelHandle
import com.kiwixrag.app.storage.PathResolver
import com.kiwixrag.app.zim.LoadReport
import com.kiwixrag.app.zim.ZimInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PhotoUi(val label: String, val fileName: String, val caption: String, val thumbnail: ImageBitmap?)

class SourceUi(
    val index: Int,
    val zimId: String,
    val zimFileName: String,
    val title: String,
    val path: String,
    val snippet: String,
    val photos: List<PhotoUi>,
)

class ChatMessage(
    val id: Long,
    val fromUser: Boolean,
    val text: String,
    val sources: List<SourceUi> = emptyList(),
    val streaming: Boolean = false,
    val isError: Boolean = false,
)

sealed interface ModelState {
    data object None : ModelState
    data class Loading(val name: String) : ModelState
    data class Ready(val name: String, val backend: String, val vision: Boolean, val viaDescriptor: Boolean) : ModelState
    data class Failed(val message: String) : ModelState
}

data class LibraryState(
    val loading: Boolean = false,
    val archives: List<ZimInfo> = emptyList(),
    val message: String? = null,
) {
    val totalBytes: Long get() = archives.sumOf { it.sizeBytes }
}

class ViewerTarget(val zimId: String, val path: String, val title: String)

class ChatViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs = ctx.ragApp.prefs
    val library = ctx.ragApp.library
    private val runner = AnswerRunner(RagPipeline(library))

    private val _model = MutableStateFlow<ModelState>(ModelState.None)
    val model: StateFlow<ModelState> = _model.asStateFlow()
    private val _libState = MutableStateFlow(LibraryState())
    val libState: StateFlow<LibraryState> = _libState.asStateFlow()
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()
    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase.asStateFlow()
    private val _viewer = MutableStateFlow<ViewerTarget?>(null)
    val viewer: StateFlow<ViewerTarget?> = _viewer.asStateFlow()
    private val _accelerator = MutableStateFlow(prefs.accelerator)
    val accelerator: StateFlow<Accelerator> = _accelerator.asStateFlow()
    private val _topArticles = MutableStateFlow(prefs.topArticles)
    val topArticles: StateFlow<Int> = _topArticles.asStateFlow()

    private var engine: LlmEngine? = null
    private var handle: ModelHandle? = null
    private var job: Job? = null
    private var nextId = 1L
    private val inferenceLock = Mutex()

    fun hasAllFilesAccess(): Boolean = Environment.isExternalStorageManager()

    /** Called once from the activity: reloads whatever the user picked last time. */
    fun restoreLast() {
        prefs.zimTreeUri?.let { loadZimFolder(Uri.parse(it), remember = false) }
        prefs.modelUri?.let { loadModel(Uri.parse(it), remember = false) }
    }

    // ------------------------------------------------------------------ model

    fun loadModel(uri: Uri, remember: Boolean = true) {
        viewModelScope.launch {
            val name = PathResolver.displayName(ctx, uri)
            _model.value = ModelState.Loading(name)
            val result = runCatching {
                withContext(Dispatchers.Default) {
                    inferenceLock.withLock {
                        engine?.close(); handle?.close(); engine = null; handle = null
                        if (remember) runCatching {
                            ctx.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        val h = ModelHandle.open(ctx, uri)
                        val e = try {
                            EngineFactory.load(ctx, h.path, h.displayName, prefs.accelerator)
                        } catch (t: Throwable) {
                            h.close(); throw t
                        }
                        handle = h; engine = e
                        ModelState.Ready(h.displayName, e.backendLabel, e.supportsVision, h.viaDescriptor)
                    }
                }
            }
            result.onSuccess {
                if (remember) prefs.modelUri = uri.toString()
                _model.value = it
            }.onFailure {
                _model.value = ModelState.Failed(it.message ?: it.javaClass.simpleName)
            }
        }
    }

    fun setAccelerator(a: Accelerator) {
        prefs.accelerator = a
        _accelerator.value = a
        prefs.modelUri?.let { loadModel(Uri.parse(it), remember = false) }
    }

    fun setTopArticles(n: Int) {
        prefs.topArticles = n
        _topArticles.value = prefs.topArticles
    }

    // -------------------------------------------------------------------- zim

    fun loadZimFolder(tree: Uri, remember: Boolean = true) {
        viewModelScope.launch {
            _libState.value = LibraryState(loading = true, message = "Opening ZIM files")
            val report: Result<LoadReport> = runCatching {
                withContext(Dispatchers.IO) {
                    if (remember) runCatching {
                        ctx.contentResolver.takePersistableUriPermission(
                            tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    val dir = PathResolver.resolveTree(tree)?.let(::File)
                    if (hasAllFilesAccess() && dir != null && dir.isDirectory) library.loadFolder(dir) else library.loadTree(tree)
                }
            }
            report.onSuccess { r ->
                if (remember) prefs.zimTreeUri = tree.toString()
                val msg = when {
                    r.opened == 0 && r.failed.isEmpty() -> "No .zim files found in that folder"
                    r.failed.isNotEmpty() -> "${r.opened} opened. Failed: ${r.failed.joinToString("; ")}"
                    else -> null
                }
                _libState.value = LibraryState(archives = library.archives.value, message = msg)
            }.onFailure {
                _libState.value = LibraryState(archives = library.archives.value, message = it.message ?: "Could not read the folder")
            }
        }
    }

    // ------------------------------------------------------------------- chat

    fun ask(question: String) {
        val q = question.trim()
        if (q.isEmpty() || job?.isActive == true) return
        val assistantId = nextId + 1
        _messages.update { it + ChatMessage(nextId, true, q) + ChatMessage(assistantId, false, "", streaming = true) }
        nextId += 2

        job = viewModelScope.launch(Dispatchers.Default) {
            var sources: List<SourceUi> = emptyList()
            try {
                if (library.archives.value.isEmpty()) {
                    finish(assistantId, "No ZIM library is loaded. Pick the folder that holds your .zim files first.", error = true)
                    return@launch
                }
                val outcome = runner.run(
                    question = q,
                    topArticles = prefs.topArticles,
                    inferenceLock = inferenceLock,
                    engineProvider = { engine },
                    onPhase = { _phase.value = it },
                    onRetrieved = { r ->
                        // Thumbnails are copied out here, before the full size bitmaps are recycled.
                        sources = toUi(r)
                        setSources(assistantId, sources)
                    },
                    onPartial = { updateText(assistantId, it) },
                )
                when (outcome) {
                    Outcome.NoResults -> finish(assistantId, "I could not find anything in the loaded Kiwix library for that question. Try different keywords.")
                    Outcome.NoModel -> finish(assistantId, "No model is loaded, so here are the matching Kiwix pages. Load a .task or .litertlm model to get an answer.", sources)
                    is Outcome.Answer -> finish(assistantId, outcome.text.ifEmpty { "The model returned no text." }, sources)
                }
            } catch (e: CancellationException) {
                finish(assistantId, currentText(assistantId).ifEmpty { "Stopped." })
                throw e
            } catch (e: Throwable) {
                finish(assistantId, "Generation failed: ${e.message ?: e.javaClass.simpleName}", error = true)
            } finally {
                _phase.value = Phase.Idle
            }
        }
    }

    fun stop() {
        job?.cancel()
    }

    fun clearChat() {
        stop()
        _messages.value = emptyList()
    }

    fun openArticle(source: SourceUi) {
        _viewer.value = ViewerTarget(source.zimId, source.path, source.title)
    }

    fun closeArticle() {
        _viewer.value = null
    }

    private fun toUi(r: Retrieval): List<SourceUi> = r.sources.map { s ->
        SourceUi(
            index = s.index, zimId = s.zimId, zimFileName = s.zimFileName, title = s.articleTitle, path = s.path,
            snippet = s.snippet.ifBlank { s.text.take(160) },
            photos = r.images.filter { it.sourceIndex == s.index }.map {
                val thumb = BitmapFactory.decodeByteArray(it.thumbnailJpeg, 0, it.thumbnailJpeg.size)?.asImageBitmap()
                PhotoUi(it.label, it.fileName, it.caption, thumb)
            },
        )
    }

    private fun setSources(id: Long, sources: List<SourceUi>) = replace(id) { ChatMessage(it.id, false, it.text, sources, it.streaming, it.isError) }

    private fun updateText(id: Long, text: String) = replace(id) { ChatMessage(it.id, false, text, it.sources, true, false) }

    private fun finish(id: Long, text: String, sources: List<SourceUi>? = null, error: Boolean = false) =
        replace(id) { ChatMessage(it.id, false, text, sources ?: it.sources, false, error) }

    private fun currentText(id: Long) = _messages.value.firstOrNull { it.id == id }?.text.orEmpty()

    private fun replace(id: Long, f: (ChatMessage) -> ChatMessage) =
        _messages.update { list -> list.map { if (it.id == id) f(it) else it } }

    override fun onCleared() {
        job?.cancel()
        engine?.close()
        handle?.close()
        library.close()
    }
}
