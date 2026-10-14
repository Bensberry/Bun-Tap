package com.buntap.app.ui.routines

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import com.buntap.app.AppContainer
import com.buntap.app.routines.ImportPlan
import com.buntap.app.routines.ImportResult
import com.buntap.app.routines.Routine
import com.buntap.app.routines.RoutineTree
import com.buntap.app.ui.BunViewModel
import com.buntap.app.ui.MessageEffect
import com.buntap.app.ui.components.Banner
import com.buntap.app.ui.components.BannerTone
import com.buntap.app.ui.components.BunTopBar
import com.buntap.app.ui.components.ConfirmDialog
import com.buntap.app.ui.components.DropdownField
import com.buntap.app.ui.components.EmptyState
import com.buntap.app.ui.components.Fmt
import com.buntap.app.ui.components.HelpText
import com.buntap.app.ui.components.ScreenColumn
import com.buntap.app.ui.components.SectionCard
import com.buntap.app.ui.components.TextInputDialog
import com.buntap.app.ui.icons.BunIcons
import com.buntap.app.ui.navigation.Navigator
import com.buntap.app.ui.navigation.bunViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class RoutineSort(val label: String) { UPDATED("Recently edited"), NAME("Name"), LAST_RUN("Last run") }

class RoutineLibraryViewModel(c: AppContainer) : BunViewModel(c) {
    val query = MutableStateFlow("")
    val sort = MutableStateFlow(RoutineSort.UPDATED)

    val routines: StateFlow<List<Routine>> = combine(c.routines.routines, query, sort) { list, q, s ->
        val filtered = if (q.isBlank()) list else list.filter { it.name.contains(q, true) || it.description.contains(q, true) }
        when (s) {
            RoutineSort.UPDATED -> filtered.sortedByDescending { it.updatedAt }
            RoutineSort.NAME -> filtered.sortedBy { it.name.lowercase() }
            RoutineSort.LAST_RUN -> filtered.sortedByDescending { it.lastRunAt ?: 0L }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val targets = c.targets.targets.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _importPlan = MutableStateFlow<ImportPlan?>(null)
    val importPlan: StateFlow<ImportPlan?> = _importPlan

    /** Routine waiting for the "create document" result. */
    var pendingExport: Routine? = null

    fun run(r: Routine, stepMode: Boolean = false) {
        viewModelScope.launch { if (r.needsReview) c.routines.markReviewed(r.id) }
        c.launcher.armRoutine(r.id, r.name) // the floating panel's Start reruns this
        startPrepared(r.id) { c.launcher.prepareRoutine(r.id, stepMode) }
    }

    fun arm(r: Routine) = armForPanel(r.name) { c.launcher.armRoutine(r.id, r.name) }
    fun rename(r: Routine, name: String) = viewModelScope.launch { c.routines.rename(r.id, name) }
    fun duplicate(r: Routine) = viewModelScope.launch { c.routines.duplicate(r.id)?.let { show("Created “${it.name}”") } }
    fun delete(r: Routine) = viewModelScope.launch {
        c.routines.delete(r.id)
        if (c.engine.armed.value?.name == r.name) c.engine.disarm()
        show("Deleted “${r.name}”")
    }

    fun export(context: Context, uri: Uri) {
        val r = pendingExport ?: return
        pendingExport = null
        viewModelScope.launch {
            val json = c.library.exportJson(r.id) ?: return@launch show("The routine no longer exists.")
            val ok = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri, "wt")?.use { it.write(json.toByteArray()) } != null }.getOrDefault(false)
            }
            show(if (ok) "Exported “${r.name}” with its target images" else "Could not write the file")
        }
    }

    fun readImport(context: Context, uri: Uri) {
        viewModelScope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        val bytes = input.readNBytesCompat(MAX_IMPORT_BYTES + 1)
                        if (bytes.size > MAX_IMPORT_BYTES) null else String(bytes)
                    }
                }.getOrNull()
            }
            if (text == null) return@launch show("The file could not be read or is too large.")
            when (val r = c.library.parseImport(text, c.settings.current.rateLimits, c.displayMonitor.current)) {
                is ImportResult.Error -> show(r.message)
                is ImportResult.Ok -> _importPlan.value = r.plan
            }
        }
    }

    fun confirmImport() {
        val plan = _importPlan.value ?: return
        _importPlan.value = null
        viewModelScope.launch {
            val routine = c.library.commitImport(plan)
            show("Imported “${routine.name}”. Review it before running.")
        }
    }

    fun cancelImport() {
        _importPlan.value = null
    }

    fun createExample(aId: String, bId: String) = viewModelScope.launch {
        val r = c.library.createExample(aId, bId)
        show(if (r != null) "Created “${r.name}”" else "Choose two existing targets")
    }

    private companion object {
        const val MAX_IMPORT_BYTES = 20 * 1024 * 1024

        fun java.io.InputStream.readNBytesCompat(limit: Int): ByteArray {
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(16 * 1024)
            var total = 0
            while (total < limit) {
                val n = read(buf, 0, minOf(buf.size, limit - total))
                if (n < 0) break
                out.write(buf, 0, n)
                total += n
            }
            return out.toByteArray()
        }
    }
}

@Composable
fun RoutineLibraryScreen(nav: Navigator) {
    val vm = bunViewModel { RoutineLibraryViewModel(it) }
    val context = LocalContext.current
    val routines by vm.routines.collectAsState()
    val targets by vm.targets.collectAsState()
    val query by vm.query.collectAsState()
    val sort by vm.sort.collectAsState()
    val plan by vm.importPlan.collectAsState()
    val engine by vm.engineState.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    var renaming by remember { mutableStateOf<Routine?>(null) }
    var deleting by remember { mutableStateOf<Routine?>(null) }
    var reviewing by remember { mutableStateOf<Routine?>(null) }
    var exampleDialog by remember { mutableStateOf(false) }
    MessageEffect(vm, snackbar)

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(context, uri) else vm.pendingExport = null
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { vm.readImport(context, it) }
    }

    Scaffold(
        topBar = {
            BunTopBar("Routines") {
                TextButton(onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream", "*/*")) }) {
                    Icon(BunIcons.Download, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                    Text("Import")
                }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { nav.routineEditor(RoutineEditorViewModel.NEW) },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("New routine") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        ScreenColumn(padding, bottomSpace = 96.dp) {
            OutlinedTextField(
                value = query,
                onValueChange = { vm.query.value = it },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                placeholder = { Text("Search routines") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                RoutineSort.entries.forEach { s -> FilterChip(selected = sort == s, onClick = { vm.sort.value = s }, label = { Text(s.label) }) }
            }

            SectionCard {
                if (routines.isEmpty()) {
                    EmptyState(
                        if (query.isBlank()) "No routines yet" else "No matches",
                        if (query.isBlank()) "Create a routine, import a .json file, or build the example routine below." else "Try another search.",
                        BunIcons.Routine,
                    )
                }
                routines.forEachIndexed { i, r ->
                    RoutineRow(
                        routine = r,
                        canRun = !engine.status.isActive,
                        onOpen = { nav.routineEditor(r.id) },
                        onRun = { if (r.needsReview) reviewing = r else vm.run(r) },
                        onStep = { vm.run(r, stepMode = true) },
                        onArm = { vm.arm(r) },
                        onRename = { renaming = r },
                        onDuplicate = { vm.duplicate(r) },
                        onExport = {
                            vm.pendingExport = r
                            exportLauncher.launch(r.name.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().ifEmpty { "routine" } + ".buntap.json")
                        },
                        onDelete = { deleting = r },
                    )
                    if (i < routines.lastIndex) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }

            SectionCard(title = "Example routine", subtitle = "Uses two of your visual targets") {
                HelpText(
                    "Waits 2 s, taps Target A three times at 5 TPS if it is visible, waits 1 s, then waits up to 10 s for " +
                        "Target B (retrying twice) and taps it five times at 2 TPS. Reports success or failure.",
                )
                OutlinedButton(onClick = { exampleDialog = true }, enabled = targets.isNotEmpty()) { Text("Create example routine") }
                if (targets.isEmpty()) HelpText("Create at least one visual target first (Targets tab).")
            }

            SectionCard(title = "Sharing routines") {
                HelpText(
                    "Export saves a versioned .buntap.json file including the images of the targets it uses. Imported files " +
                        "are validated (action types, numbers, loop limits, images) and never run code. They must be reviewed before running.",
                )
            }
        }
    }

    plan?.let { p ->
        AlertDialog(
            onDismissRequest = vm::cancelImport,
            title = { Text("Import “${p.routineName}”?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("${RoutineTree.all(p.body.actions).count()} actions, ${p.targets.size} target image(s).")
                    if (p.description.isNotBlank()) HelpText(p.description)
                    p.warnings.forEach { Banner(it, BannerTone.WARNING) }
                    HelpText("The routine will be marked for review; check every action before running it.")
                }
            },
            confirmButton = { TextButton(onClick = vm::confirmImport) { Text("Import") } },
            dismissButton = { TextButton(onClick = vm::cancelImport) { Text("Cancel") } },
        )
    }
    renaming?.let { r -> TextInputDialog("Rename routine", r.name, "Name", onConfirm = { vm.rename(r, it) }, onDismiss = { renaming = null }) }
    deleting?.let { r ->
        ConfirmDialog("Delete “${r.name}”?", "This routine will be permanently deleted. Its targets are kept.", "Delete", { vm.delete(r) }, { deleting = null }, destructive = true)
    }
    reviewing?.let { r ->
        ConfirmDialog(
            "Run imported routine?",
            "“${r.name}” was imported from a file. Open it and review its actions before running. Run it anyway?",
            "I reviewed it — run",
            { vm.run(r) },
            { reviewing = null },
        )
    }
    if (exampleDialog) {
        var a by remember { mutableStateOf(targets.firstOrNull()?.id) }
        var b by remember { mutableStateOf(targets.getOrNull(1)?.id ?: targets.firstOrNull()?.id) }
        val options = targets.map { it.id to it.name }
        AlertDialog(
            onDismissRequest = { exampleDialog = false },
            title = { Text("Create example routine") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    DropdownField("Target A", options, a, { a = it })
                    DropdownField("Target B", options, b, { b = it })
                }
            },
            confirmButton = {
                TextButton(enabled = a != null && b != null, onClick = {
                    exampleDialog = false
                    vm.createExample(a!!, b!!)
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { exampleDialog = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun RoutineRow(
    routine: Routine,
    canRun: Boolean,
    onOpen: () -> Unit,
    onRun: () -> Unit,
    onStep: () -> Unit,
    onArm: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(routine.name, style = MaterialTheme.typography.bodyLarge)
            if (routine.description.isNotBlank()) HelpText(routine.description.take(120))
            val count = RoutineTree.all(routine.body.actions).count()
            HelpText(
                "$count action${if (count == 1) "" else "s"} · " +
                    (routine.lastRunAt?.let { "last run ${Fmt.relative(it)} (${routine.lastRunStatus?.lowercase()})" } ?: "never run") +
                    if (routine.needsReview) " · imported, needs review" else "",
            )
        }
        IconButton(onClick = onRun, enabled = canRun) { Icon(Icons.Filled.PlayArrow, contentDescription = "Run ${routine.name}") }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More for ${routine.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Edit") }, onClick = { menu = false; onOpen() })
                DropdownMenuItem(text = { Text("Run step by step") }, enabled = canRun, onClick = { menu = false; onStep() })
                DropdownMenuItem(text = { Text("Arm floating panel") }, onClick = { menu = false; onArm() })
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Duplicate") }, onClick = { menu = false; onDuplicate() })
                DropdownMenuItem(text = { Text("Export") }, onClick = { menu = false; onExport() })
                DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
            }
        }
    }
}
