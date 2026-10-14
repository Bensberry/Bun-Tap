package com.buntap.app.ui.targets

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.capture.CaptureState
import com.buntap.app.domain.model.DetectionMode
import com.buntap.app.domain.model.RunStatus
import com.buntap.app.domain.model.SessionKind
import com.buntap.app.domain.model.VisualTarget
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.Banner
import com.buntap.app.ui.components.BannerTone
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.ConfirmDialog
import com.buntap.app.ui.components.CoreStats
import com.buntap.app.ui.components.EmptyState
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.SessionControls
import com.buntap.app.ui.components.SessionHeader
import com.buntap.app.ui.components.StatusPill
import com.buntap.app.ui.components.TextInputDialog
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.navigation.AppActions
import com.buntap.app.ui.navigation.LocalAppActions
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the crop screen is used for. */
enum class CropMode { TARGET, REGION, POINT }

fun DetectionMode.label(): String = when (this) {
    DetectionMode.ONCE_PER_APPEARANCE -> "Tap once per appearance"
    DetectionMode.REPEAT_WHILE_VISIBLE -> "Repeat while visible"
    DetectionMode.WAIT_AND_CLICK -> "Wait and tap"
    DetectionMode.NOTIFY_ONLY -> "Notify only"
}

class TargetsViewModel(c: AppContainer) : BunViewModel(c) {
    val targets: StateFlow<List<VisualTarget>> = c.targets.targets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val capture = c.capture.state

    private val _thumbnails = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val thumbnails: StateFlow<Map<String, ImageBitmap>> = _thumbnails
    private val loadedVersions = HashMap<String, Long>()

    private val _cropRequests = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val cropRequests: SharedFlow<String> = _cropRequests

    private val _countdown = MutableStateFlow<Int?>(null)
    val countdown: StateFlow<Int?> = _countdown

    init {
        viewModelScope.launch {
            targets.collect { list ->
                val missing = list.filter { loadedVersions[it.id] != it.updatedAt }
                if (missing.isEmpty()) return@collect
                val loaded = withContext(Dispatchers.IO) {
                    missing.mapNotNull { t -> c.images.decodeThumbnail(t.imageFile)?.let { t.id to it.asImageBitmap() } }
                }
                missing.forEach { loadedVersions[it.id] = it.updatedAt }
                _thumbnails.value = _thumbnails.value + loaded
            }
        }
    }

    /** Hides BunTap, waits, captures the screen, then returns to the crop screen. */
    fun delayedCapture(actions: AppActions, seconds: Int = 3) {
        if (!c.capture.active.value) {
            show("Start screen capture first.")
            return
        }
        actions.moveToBackground()
        c.appScope.launch {
            for (i in seconds downTo 1) {
                _countdown.value = i
                delay(1_000)
            }
            _countdown.value = null
            val bmp = c.capture.snapshotBitmap()
            if (bmp == null) {
                c.notifications.showAlert("BunTap", "No screen frame was available. Is screen capture still running?")
                return@launch
            }
            val file = c.images.saveTempScreenshot(bmp)
            bmp.recycle()
            withContext(Dispatchers.Main) { actions.bringToFront(file.path) }
            // Fallback when Android does not allow returning to BunTap automatically.
            c.notifications.showCaptureReady(file.path)
        }
    }

    fun importImage(context: Context, uri: Uri) {
        viewModelScope.launch {
            val bmp = withContext(Dispatchers.IO) { decodeUri(context, uri) }
            if (bmp == null) {
                show("That file is not a supported image.")
                return@launch
            }
            val file = withContext(Dispatchers.IO) { c.images.saveTempScreenshot(bmp) }
            bmp.recycle()
            _cropRequests.tryEmit(file.path)
        }
    }

    private fun decodeUri(context: Context, uri: Uri): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0) {
            null
        } else {
            var sample = 1
            while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        }
    } catch (e: Exception) {
        null
    }

    fun setEnabled(id: String, enabled: Boolean) = viewModelScope.launch { c.targets.setEnabled(id, enabled) }
    fun rename(id: String, name: String) = viewModelScope.launch { c.targets.rename(id, name) }
    fun duplicate(id: String) = viewModelScope.launch { c.targets.duplicate(id)?.let { show("Duplicated as “${it.name}”") } }
    fun delete(id: String) = viewModelScope.launch {
        c.targets.delete(id)
        c.vision.clearCache()
        show("Target deleted")
    }

    fun stopCapture() = c.capture.stop()
    fun startVisual() {
        c.launcher.armVisual() // the floating panel's Start reruns this
        startPrepared { c.launcher.prepareVisual() }
    }
    fun armVisual() = armForPanel("Visual automation") { c.launcher.armVisual() }
}

@Composable
fun TargetsScreen(nav: Navigator) {
    val vm = bunViewModel { TargetsViewModel(it) }
    val actions = LocalAppActions.current
    val targets by vm.targets.collectAsState()
    val thumbs by vm.thumbnails.collectAsState()
    val capture by vm.capture.collectAsState()
    val engine by vm.engineState.collectAsState()
    val countdown by vm.countdown.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<VisualTarget?>(null) }
    var deleting by remember { mutableStateOf<VisualTarget?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importImage(context, it) } }
    MessageEffect(vm, snackbar)
    LaunchedEffect(vm) { vm.cropRequests.collect { nav.crop(it, CropMode.TARGET) } }

    Scaffold(
        topBar = { BunTopBar("Visual targets") { IconButton(onClick = nav::monitor) { Icon(BunIcons.Speed, contentDescription = "Monitor") } } },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ScreenColumn(padding) {
            SectionCard(
                title = "Screen capture",
                trailing = {
                    StatusPill(
                        if (capture is CaptureState.Active) RunStatus.RUNNING else RunStatus.IDLE,
                        when (capture) {
                            is CaptureState.Active -> "Active"
                            CaptureState.Starting -> "Starting"
                            else -> "Off"
                        },
                    )
                },
            ) {
                when (val c = capture) {
                    is CaptureState.Active -> HelpText("Capturing ${c.geometry.describe()}. Frames are analysed on this device only.")
                    is CaptureState.Ended -> HelpText("${c.reason}. Start it again to capture or detect targets.")
                    else -> HelpText("Needed to capture targets and to detect them. Android asks every time; choose “Entire screen”.")
                }
                countdown?.let { Banner("Capturing in $it s — switch to the app with the button you want.", BannerTone.INFO) }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (capture is CaptureState.Active) {
                        Button(onClick = { vm.delayedCapture(actions) }, modifier = Modifier.weight(1f)) {
                            Icon(BunIcons.Camera, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Capture in 3 s")
                        }
                        OutlinedButton(onClick = vm::stopCapture) { Text("Stop") }
                    } else {
                        Button(onClick = actions.requestCapture, modifier = Modifier.weight(1f)) { Text("Start screen capture") }
                    }
                }
                OutlinedButton(onClick = { picker.launch(arrayOf("image/png", "image/jpeg", "image/webp", "image/*")) }, modifier = Modifier.fillMaxWidth()) {
                    Icon(BunIcons.Upload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Import an image file")
                }
                HelpText("Tip: the floating panel's camera button captures the screen without leaving the app you are in.")
            }

            SectionCard(title = "Saved targets", subtitle = "Enabled targets are watched by visual automation") {
                if (targets.isEmpty()) {
                    EmptyState("No targets yet", "Capture the screen or import an image, then crop the button or icon to recognise.", BunIcons.Image)
                }
                targets.forEachIndexed { i, t ->
                    TargetRow(
                        target = t,
                        thumbnail = thumbs[t.id],
                        onOpen = { nav.targetEditor(t.id) },
                        onEnabled = { vm.setEnabled(t.id, it) },
                        onRename = { renaming = t },
                        onDuplicate = { vm.duplicate(t.id) },
                        onDelete = { deleting = t },
                    )
                    if (i < targets.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            SectionCard(title = "Visual automation", subtitle = "Watch all enabled targets and react to them") {
                HelpText(
                    "Runs until you stop it, a wait-and-tap target times out, or a safety limit is reached. Template matching can " +
                        "fail if the button changes size, colour, theme or language; check targets with “Test detection” first.",
                )
                if (engine.kind == SessionKind.VISUAL && (engine.status.isActive || engine.stats.detections > 0)) {
                    SessionHeader(engine)
                    CoreStats(engine.stats)
                    engine.lastDetection?.let { HelpText("Last detection: “${it.targetName}” score ${Fmt.score(it.score)} at ${it.rect}") }
                }
                if (!engine.status.isActive) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = vm::startVisual, enabled = targets.any { it.enabled }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Start watching")
                        }
                        OutlinedButton(onClick = vm::armVisual, enabled = targets.any { it.enabled }, modifier = Modifier.weight(1f)) {
                            Text("Arm floating panel")
                        }
                    }
                }
                SessionControls(engine, vm::pause, vm::resume, vm::stop, vm::emergencyStop)
            }
        }
    }

    renaming?.let { t ->
        TextInputDialog("Rename target", t.name, "Name", onConfirm = { vm.rename(t.id, it) }, onDismiss = { renaming = null })
    }
    deleting?.let { t ->
        ConfirmDialog(
            "Delete “${t.name}”?",
            "The target and its image will be deleted. Routines that use it will no longer run until you choose another target.",
            "Delete",
            { vm.delete(t.id) },
            { deleting = null },
            destructive = true,
        )
    }
}

@Composable
private fun TargetRow(
    target: VisualTarget,
    thumbnail: ImageBitmap?,
    onOpen: () -> Unit,
    onEnabled: (Boolean) -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(56.dp).background(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.shapes.small),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbnail != null) {
                Image(thumbnail, contentDescription = "Image of ${target.name}", contentScale = ContentScale.Fit, modifier = Modifier.size(52.dp))
            } else {
                Icon(BunIcons.Image, contentDescription = null)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(target.name, style = MaterialTheme.typography.bodyLarge)
            HelpText("${target.action.mode.label()} · threshold ${Fmt.score(target.match.threshold)} · ${target.width}×${target.height} px")
        }
        Switch(checked = target.enabled, onCheckedChange = onEnabled)
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${target.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
