package chat.stoat.fork.e2ee.ui

import android.content.ContentResolver
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import chat.stoat.api.StoatAPI
import chat.stoat.api.fork.e2ee.E2eeChannels
import chat.stoat.api.fork.e2ee.E2eeCore
import chat.stoat.api.fork.e2ee.E2eeKeyExport
import chat.stoat.api.fork.e2ee.E2eeKeyImport
import chat.stoat.api.internals.ChannelUtils
import chat.stoat.fork.e2ee.E2eeKeyFiles
import chat.stoat.fork.e2ee.E2eeManager
import chat.stoat.fork.e2ee.KeyFileFailure
import chat.stoat.fork.e2ee.PassphraseStrength
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_keys_all_chats
import chat.stoat.shared.resources.durio_keys_choose_file
import chat.stoat.shared.resources.durio_keys_export
import chat.stoat.shared.resources.durio_keys_export_action
import chat.stoat.shared.resources.durio_keys_export_cancelled
import chat.stoat.shared.resources.durio_keys_export_intro
import chat.stoat.shared.resources.durio_keys_exported
import chat.stoat.shared.resources.durio_keys_failed
import chat.stoat.shared.resources.durio_keys_file_access
import chat.stoat.shared.resources.durio_keys_file_chosen
import chat.stoat.shared.resources.durio_keys_file_too_large
import chat.stoat.shared.resources.durio_keys_import
import chat.stoat.shared.resources.durio_keys_import_action
import chat.stoat.shared.resources.durio_keys_import_intro
import chat.stoat.shared.resources.durio_keys_imported
import chat.stoat.shared.resources.durio_keys_imported_note
import chat.stoat.shared.resources.durio_keys_imported_skipped
import chat.stoat.shared.resources.durio_keys_importing
import chat.stoat.shared.resources.durio_keys_invalid_file
import chat.stoat.shared.resources.durio_keys_no_chats
import chat.stoat.shared.resources.durio_keys_passphrase
import chat.stoat.shared.resources.durio_keys_passphrase_mismatch
import chat.stoat.shared.resources.durio_keys_passphrase_repeat
import chat.stoat.shared.resources.durio_keys_strength_fair
import chat.stoat.shared.resources.durio_keys_strength_strong
import chat.stoat.shared.resources.durio_keys_strength_too_short
import chat.stoat.shared.resources.durio_keys_strength_weak
import chat.stoat.shared.resources.durio_keys_too_many
import chat.stoat.shared.resources.durio_keys_working
import chat.stoat.shared.resources.durio_keys_wrong_passphrase
import chat.stoat.shared.resources.durio_verify_close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

class KeyExportViewModel : ViewModel() {
    var passphrase by mutableStateOf("")
    var repeated by mutableStateOf("")
    var allChats by mutableStateOf(true)
    val selected = mutableStateListOf<String>()
    var busy by mutableStateOf(false)
    var failure by mutableStateOf<KeyFileFailure?>(null)
    var savedSessions by mutableStateOf<Int?>(null)
    var cancelled by mutableStateOf(false)

    // The file (ciphertext) between the export and the save dialog; dropped right after
    private var pending: E2eeKeyExport? = null

    /** Exports (PBKDF2 off the UI thread), then [onReady] opens the save dialog. */
    fun export(core: E2eeCore? = E2eeManager.active?.core, onReady: (fileName: String) -> Unit) {
        if (core == null) {
            failure = KeyFileFailure.NoDevice
            return
        }
        if (!E2eeKeyFiles.canExport(passphrase, repeated)) return
        busy = true
        failure = null
        cancelled = false
        savedSessions = null
        // wiped by E2eeKeyFiles.export
        val bytes = passphrase.encodeToByteArray()
        val channels = if (allChats) emptyList() else selected.toList()
        viewModelScope.launch {
            when (val result = E2eeKeyFiles.export(core, bytes, channels)) {
                is E2eeKeyFiles.Exported.Ready -> {
                    pending = result.export
                    passphrase = ""
                    repeated = ""
                    onReady(E2eeKeyFiles.fileName())
                }
                is E2eeKeyFiles.Exported.Failed -> {
                    failure = result.reason
                    busy = false
                }
            }
        }
    }

    /** The save dialog's answer; null: cancelled, the file is dropped. */
    fun save(resolver: ContentResolver, uri: Uri?) {
        val export = pending ?: return
        pending = null
        if (uri == null) {
            cancelled = true
            busy = false
            return
        }
        viewModelScope.launch {
            val written = withContext(Dispatchers.IO) {
                try {
                    resolver.openOutputStream(uri, "wt")?.use { it.write(export.data.encodeToByteArray()) } != null
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    false
                }
            }
            if (written) savedSessions = export.sessions else failure = KeyFileFailure.FileAccess
            busy = false
        }
    }

    override fun onCleared() {
        pending = null
    }
}

/**
 * "Export keys" (plan CR-21): the passphrase twice with a strength check, all encrypted chats or
 * some of them, then the file is saved where the user picks (Storage Access Framework, `.txt`).
 * The window is secure while the passphrase is on screen.
 */
@Composable
fun KeyExportScreen(navController: NavController, viewModel: KeyExportViewModel = viewModel()) {
    val context = LocalContext.current
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        viewModel.save(context.contentResolver, uri)
    }
    val chats = remember { encryptedChats() }
    SecureWindow()

    RecoveryScaffold(Res.string.durio_keys_export, onBack = { navController.popBackStack() }) {
        val saved = viewModel.savedSessions
        if (saved != null) {
            Text(stringResource(Res.string.durio_keys_exported, saved), style = MaterialTheme.typography.titleMedium)
            Button(onClick = { navController.popBackStack() }) { Text(stringResource(Res.string.durio_verify_close)) }
            return@RecoveryScaffold
        }
        Text(stringResource(Res.string.durio_keys_export_intro))
        PassphraseField(viewModel.passphrase, Res.string.durio_keys_passphrase, "passphrase") { viewModel.passphrase = it }
        val strength = E2eeKeyFiles.strength(viewModel.passphrase)
        if (viewModel.passphrase.isNotEmpty()) {
            Text(
                when (strength) {
                    PassphraseStrength.TooShort -> stringResource(Res.string.durio_keys_strength_too_short, E2eeKeyFiles.MIN_PASSPHRASE)
                    PassphraseStrength.Weak -> stringResource(Res.string.durio_keys_strength_weak)
                    PassphraseStrength.Fair -> stringResource(Res.string.durio_keys_strength_fair)
                    PassphraseStrength.Strong -> stringResource(Res.string.durio_keys_strength_strong)
                },
                color = if (strength < PassphraseStrength.Fair) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        PassphraseField(viewModel.repeated, Res.string.durio_keys_passphrase_repeat, "repeated") { viewModel.repeated = it }
        if (viewModel.repeated.isNotEmpty() && viewModel.repeated != viewModel.passphrase) {
            Text(stringResource(Res.string.durio_keys_passphrase_mismatch), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        if (chats.isEmpty()) {
            Text(stringResource(Res.string.durio_keys_no_chats), style = MaterialTheme.typography.bodySmall)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = viewModel.allChats, onCheckedChange = { viewModel.allChats = it })
                Text(stringResource(Res.string.durio_keys_all_chats))
            }
            if (!viewModel.allChats) {
                Column {
                    chats.forEach { (id, name) ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = id in viewModel.selected,
                                onCheckedChange = { if (it) viewModel.selected += id else viewModel.selected -= id },
                            )
                            Text(name)
                        }
                    }
                }
            }
        }

        Button(
            enabled = !viewModel.busy && E2eeKeyFiles.canExport(viewModel.passphrase, viewModel.repeated) &&
                (viewModel.allChats || chats.isEmpty() || viewModel.selected.isNotEmpty()),
            onClick = { viewModel.export { name -> save.launch(name) } },
        ) { Text(stringResource(Res.string.durio_keys_export_action)) }
        if (viewModel.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(stringResource(Res.string.durio_keys_working))
            }
        }
        if (viewModel.cancelled) Hint(stringResource(Res.string.durio_keys_export_cancelled), warning = false, padded = false)
        viewModel.failure?.let { Hint(stringResource(failureText(it)), warning = true, padded = false) }
    }
}

class KeyImportViewModel : ViewModel() {
    var file by mutableStateOf<Uri?>(null)
    var passphrase by mutableStateOf("")
    var busy by mutableStateOf(false)
    var failure by mutableStateOf<KeyFileFailure?>(null)
    var result by mutableStateOf<E2eeKeyImport?>(null)

    /** Reads the chosen file (at most 64 MiB) and imports it; PBKDF2 off the UI thread. */
    fun import(resolver: ContentResolver, core: E2eeCore? = E2eeManager.active?.core) {
        val uri = file ?: return
        if (core == null) {
            failure = KeyFileFailure.NoDevice
            return
        }
        busy = true
        failure = null
        val bytes = passphrase.encodeToByteArray()
        viewModelScope.launch {
            val content = withContext(Dispatchers.IO) {
                try {
                    resolver.openInputStream(uri)?.use { ReadFile.Read(E2eeKeyFiles.readCapped(it)) } ?: ReadFile.Failed
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    ReadFile.Failed
                }
            }
            val outcome = when {
                content !is ReadFile.Read -> E2eeKeyFiles.Imported.Failed(KeyFileFailure.FileAccess)
                content.bytes == null -> E2eeKeyFiles.Imported.Failed(KeyFileFailure.TooLarge)
                else -> E2eeKeyFiles.import(core, content.bytes, bytes)
            }
            bytes.fill(0)
            when (outcome) {
                is E2eeKeyFiles.Imported.Done -> {
                    result = outcome.result
                    passphrase = ""
                }
                is E2eeKeyFiles.Imported.Failed -> failure = outcome.reason
            }
            busy = false
        }
    }

    private sealed interface ReadFile {
        class Read(val bytes: ByteArray?) : ReadFile
        data object Failed : ReadFile
    }
}

/**
 * "Import keys" (plan CR-21): a key file (SAF; read up to 64 MiB) and its passphrase. A wrong
 * passphrase (or a modified file) and a file that is not a key file are told apart; messages
 * waiting for the imported keys are decrypted again.
 */
@Composable
fun KeyImportScreen(navController: NavController, viewModel: KeyImportViewModel = viewModel()) {
    val context = LocalContext.current
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            viewModel.file = uri
            viewModel.failure = null
        }
    }
    SecureWindow()

    RecoveryScaffold(Res.string.durio_keys_import, onBack = { navController.popBackStack() }) {
        val done = viewModel.result
        if (done != null) {
            Text(stringResource(Res.string.durio_keys_imported, done.imported, done.total), style = MaterialTheme.typography.titleMedium)
            if (done.skipped > 0) Text(stringResource(Res.string.durio_keys_imported_skipped, done.skipped))
            Text(stringResource(Res.string.durio_keys_imported_note))
            Button(onClick = { navController.popBackStack() }) { Text(stringResource(Res.string.durio_verify_close)) }
            return@RecoveryScaffold
        }
        Text(stringResource(Res.string.durio_keys_import_intro))
        FilledTonalButton(onClick = { pick.launch(arrayOf("text/plain", "application/octet-stream", "*/*")) }) {
            Text(stringResource(Res.string.durio_keys_choose_file))
        }
        if (viewModel.file != null) Text(stringResource(Res.string.durio_keys_file_chosen), style = MaterialTheme.typography.bodySmall)
        PassphraseField(viewModel.passphrase, Res.string.durio_keys_passphrase, "passphrase") {
            viewModel.passphrase = it
            viewModel.failure = null
        }
        Button(
            enabled = !viewModel.busy && viewModel.file != null && viewModel.passphrase.isNotEmpty(),
            onClick = { viewModel.import(context.contentResolver) },
        ) { Text(stringResource(Res.string.durio_keys_import_action)) }
        if (viewModel.busy) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator()
                Text(stringResource(Res.string.durio_keys_importing))
            }
        }
        viewModel.failure?.let { Hint(stringResource(failureText(it)), warning = true, padded = false) }
    }
}

@Composable
private fun PassphraseField(value: String, label: StringResource, tag: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(stringResource(label)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        // no suggestions, no learning: it is a secret
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Encrypted DMs/groups known so far, with a name to show. */
private fun encryptedChats(): List<Pair<String, String>> =
    E2eeChannels.encryptedWithMembers().map { (id, _) ->
        val name = StoatAPI.channelCache[id]?.let { runCatching { ChannelUtils.resolveName(it) }.getOrNull() }
        id to (name ?: id)
    }

private fun failureText(failure: KeyFileFailure): StringResource = when (failure) {
    KeyFileFailure.TooManySessions -> Res.string.durio_keys_too_many
    KeyFileFailure.WrongPassphrase -> Res.string.durio_keys_wrong_passphrase
    KeyFileFailure.InvalidFile -> Res.string.durio_keys_invalid_file
    KeyFileFailure.TooLarge -> Res.string.durio_keys_file_too_large
    KeyFileFailure.FileAccess -> Res.string.durio_keys_file_access
    KeyFileFailure.NoDevice, KeyFileFailure.Other -> Res.string.durio_keys_failed
}
