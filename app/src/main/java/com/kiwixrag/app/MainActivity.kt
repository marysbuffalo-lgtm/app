package com.kiwixrag.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.kiwixrag.app.ui.ArticleViewer
import com.kiwixrag.app.ui.ChatScreen
import com.kiwixrag.app.ui.ChatViewModel
import com.kiwixrag.app.rag.Phase

class MainActivity : ComponentActivity() {
    private val vm: ChatViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) vm.restoreLast()
        setContent { AppContent() }
    }

    @Composable
    private fun AppContent() {
        val dark = isSystemInDarkTheme()
        MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme()) {
            Surface {
                val phase by vm.phase.collectAsState()
                val viewer by vm.viewer.collectAsState()

                // Keep the screen on while a question is being answered so Android does not suspend inference.
                DisposableEffect(phase) {
                    if (phase != Phase.Idle) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    onDispose { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }

                val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
                    uri?.let(vm::loadModel)
                }
                val pickZim = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri: Uri? ->
                    uri?.let { vm.loadZimFolder(it) }
                }

                val target = viewer
                if (target != null) {
                    ArticleViewer(vm.library, target, onClose = vm::closeArticle)
                } else {
                    ChatScreen(
                        vm = vm,
                        onPickModel = { pickModel.launch(arrayOf("application/octet-stream", "*/*")) },
                        onPickZimFolder = { pickZim.launch(null) },
                        onGrantAllFiles = {
                            startActivity(
                                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")),
                            )
                        },
                    )
                }
            }
        }
    }
}
