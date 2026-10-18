package chat.stoat.fork.e2ee.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import chat.stoat.R
import chat.stoat.api.fork.e2ee.E2eeAction
import chat.stoat.api.fork.e2ee.E2eeException
import chat.stoat.api.fork.e2ee.E2eeRecoveryKeyCheck
import chat.stoat.api.fork.e2ee.E2eeRecoverySetup
import chat.stoat.composables.mfa.MfaPromptSheet
import chat.stoat.composables.mfa.MfaPromptState
import chat.stoat.fork.e2ee.E2eeManager
import chat.stoat.fork.e2ee.E2eeRecovery
import chat.stoat.fork.e2ee.RecoveryFailure
import chat.stoat.fork.e2ee.SensitiveClipboard
import chat.stoat.fork.e2ee.recoveryFailure
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.back
import chat.stoat.shared.resources.durio_e2ee_cancel
import chat.stoat.shared.resources.durio_recovery_activate
import chat.stoat.shared.resources.durio_recovery_copy
import chat.stoat.shared.resources.durio_recovery_copy_warning
import chat.stoat.shared.resources.durio_recovery_copy_warning_title
import chat.stoat.shared.resources.durio_recovery_copied
import chat.stoat.shared.resources.durio_recovery_create
import chat.stoat.shared.resources.durio_recovery_done
import chat.stoat.shared.resources.durio_recovery_done_text
import chat.stoat.shared.resources.durio_recovery_exists
import chat.stoat.shared.resources.durio_recovery_failed_mismatch
import chat.stoat.shared.resources.durio_recovery_failed_network
import chat.stoat.shared.resources.durio_recovery_failed_no_backup
import chat.stoat.shared.resources.durio_recovery_failed_other
import chat.stoat.shared.resources.durio_recovery_failed_typo
import chat.stoat.shared.resources.durio_recovery_failed_wrong
import chat.stoat.shared.resources.durio_recovery_input
import chat.stoat.shared.resources.durio_recovery_input_key
import chat.stoat.shared.resources.durio_recovery_input_passphrase
import chat.stoat.shared.resources.durio_recovery_input_typo
import chat.stoat.shared.resources.durio_recovery_intro
import chat.stoat.shared.resources.durio_recovery_key_text
import chat.stoat.shared.resources.durio_recovery_key_title
import chat.stoat.shared.resources.durio_recovery_restore
import chat.stoat.shared.resources.durio_recovery_restore_done
import chat.stoat.shared.resources.durio_recovery_restore_intro
import chat.stoat.shared.resources.durio_recovery_restore_progress
import chat.stoat.shared.resources.durio_recovery_restore_title
import chat.stoat.shared.resources.durio_recovery_rotate_intro
import chat.stoat.shared.resources.durio_recovery_rotate_title
import chat.stoat.shared.resources.durio_recovery_save_file
import chat.stoat.shared.resources.durio_recovery_saved
import chat.stoat.shared.resources.durio_recovery_title
import chat.stoat.shared.resources.durio_recovery_unauthenticated_note
import chat.stoat.shared.resources.durio_recovery_written
import chat.stoat.shared.resources.durio_verify_close
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import logcat.LogPriority
import logcat.logcat
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource

class RecoverySetupViewModel : ViewModel() {
    enum class Step { Intro, Key, Activating, Done }

    val mfa = MfaPromptState()
    var step by mutableStateOf(Step.Intro)
    var busy by mutableStateOf(false)
    var failure by mutableStateOf<RecoveryFailure?>(null)

    /** A backup is on the server already: replacing it needs MFA (and the old key stops working). */
    var existsOnServer by mutableStateOf<Boolean?>(null)

    // Secret: in memory only, dropped when the screen goes
    var setup by mutableStateOf<E2eeRecoverySetup?>(null)
        private set

    fun load() {
        viewModelScope.launch {
            existsOnServer = try {
                E2eeRecovery.backup()?.exists()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
        }
    }

    fun create(rotate: Boolean) {
        val backup = E2eeRecovery.backup() ?: return
        busy = true
        failure = null
        viewModelScope.launch {
            try {
                setup = if (rotate) backup.rotate() else backup.create()
                // a rotation keeps the key the user has: nothing to show
                step = if (rotate) Step.Activating else Step.Key
                if (rotate) activate(rotate = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Recovery not created: ${e::class.simpleName}" }
                failure = recoveryFailure(e)
            }
            busy = false
        }
    }

    /**
     * Creates the backup on the server. A backup there already (or a rotation) needs an MFA
     * ticket, asked for up front: without one the server answers 401, which the account's
     * client would take for an expired session.
     */
    fun activate(rotate: Boolean = false) {
        val backup = E2eeRecovery.backup() ?: return
        val current = setup ?: return
        step = Step.Activating
        busy = true
        failure = null
        viewModelScope.launch {
            try {
                val needsTicket = rotate || existsOnServer != false
                var ticket = if (needsTicket) mfa.request()?.token ?: run {
                    step = if (rotate) Step.Intro else Step.Key
                    busy = false
                    return@launch
                } else null
                try {
                    backup.activate(current, ticket)
                } catch (e: E2eeException.ActionRequired) {
                    if (e.action != E2eeAction.MfaRequired || ticket != null) throw e
                    // a backup appeared meanwhile (another device)
                    ticket = mfa.request()?.token ?: run {
                        step = Step.Key
                        busy = false
                        return@launch
                    }
                    backup.activate(current, ticket)
                }
                setup = null
                step = Step.Done
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                logcat(LogPriority.WARN) { "Recovery not activated: ${e::class.simpleName}" }
                failure = recoveryFailure(e)
                step = if (rotate) Step.Intro else Step.Key
            }
            busy = false
        }
    }
}

/**
 * Setting up the recovery key (plan AN-32): what it is → the key, shown once (copy with a
 * warning, save to a file, "I wrote it down"; the window is secure) → the backup is created on
 * the server (MFA when one exists) → done. [rotate]: a new backup under the same key.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoverySetupScreen(navController: NavController, rotate: Boolean, viewModel: RecoverySetupViewModel = viewModel()) {
    LaunchedEffect(Unit) { viewModel.load() }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var written by remember { mutableStateOf(false) }
    var copyWarning by remember { mutableStateOf(false) }
    val saveFile = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        val key = viewModel.setup?.recoveryKey ?: return@rememberLauncherForActivityResult
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(uri)?.use { it.write((key + "\n").toByteArray()) } != null
                } catch (e: Exception) {
                    false
                }
            }
            if (ok) Toast.makeText(context, getString(Res.string.durio_recovery_saved), Toast.LENGTH_SHORT).show()
        }
    }

    MfaPromptSheet(viewModel.mfa)
    if (copyWarning) {
        AlertDialog(
            onDismissRequest = { copyWarning = false },
            title = { Text(stringResource(Res.string.durio_recovery_copy_warning_title)) },
            text = { Text(stringResource(Res.string.durio_recovery_copy_warning)) },
            confirmButton = {
                Button(onClick = {
                    copyWarning = false
                    viewModel.setup?.recoveryKey?.let { SensitiveClipboard.copy(context, it) }
                    scope.launch { Toast.makeText(context, getString(Res.string.durio_recovery_copied), Toast.LENGTH_SHORT).show() }
                }) { Text(stringResource(Res.string.durio_recovery_copy)) }
            },
            dismissButton = { TextButton(onClick = { copyWarning = false }) { Text(stringResource(Res.string.durio_e2ee_cancel)) } },
        )
    }

    RecoveryScaffold(
        title = if (rotate) Res.string.durio_recovery_rotate_title else Res.string.durio_recovery_title,
        onBack = { navController.popBackStack() },
    ) {
        when (viewModel.step) {
            RecoverySetupViewModel.Step.Intro -> {
                Text(stringResource(if (rotate) Res.string.durio_recovery_rotate_intro else Res.string.durio_recovery_intro))
                if (!rotate && viewModel.existsOnServer == true) Hint(stringResource(Res.string.durio_recovery_exists), warning = true, padded = false)
                Button(enabled = !viewModel.busy && E2eeManager.active != null, onClick = { viewModel.create(rotate) }) {
                    Text(stringResource(Res.string.durio_recovery_create))
                }
            }
            RecoverySetupViewModel.Step.Key -> {
                // the key is on screen: no screenshots, no "Recents" preview (always, not an option)
                SecureWindow()
                Text(stringResource(Res.string.durio_recovery_key_title), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.durio_recovery_key_text))
                Text(
                    viewModel.setup?.recoveryKey.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilledTonalButton(onClick = { copyWarning = true }) { Text(stringResource(Res.string.durio_recovery_copy)) }
                    FilledTonalButton(onClick = { saveFile.launch("dur.io-recovery-key.txt") }) { Text(stringResource(Res.string.durio_recovery_save_file)) }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = written, onCheckedChange = { written = it })
                    Text(stringResource(Res.string.durio_recovery_written))
                }
                Button(enabled = written && !viewModel.busy, onClick = { viewModel.activate() }) {
                    Text(stringResource(Res.string.durio_recovery_activate))
                }
            }
            RecoverySetupViewModel.Step.Activating -> CircularProgressIndicator()
            RecoverySetupViewModel.Step.Done -> {
                Text(stringResource(Res.string.durio_recovery_done), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.durio_recovery_done_text))
                Button(onClick = { navController.popBackStack() }) { Text(stringResource(Res.string.durio_verify_close)) }
            }
        }
        viewModel.failure?.let { Hint(stringResource(failureText(it)), warning = true, padded = false) }
    }
}

/**
 * "Restore history" (plan AN-32): the recovery key (or its passphrase) → the backup is unlocked
 * on this device and its keys imported, with progress; messages waiting for keys show up.
 * Restored messages carry "sender not confirmed" (the backup can be written by anybody with
 * its public key, ADR 0001).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecoveryRestoreScreen(
    navController: NavController,
    // null without an E2EE device; the input is wiped by the caller
    keyCheck: (ByteArray) -> E2eeRecoveryKeyCheck? = { E2eeManager.active?.core?.recoveryKeyCheck(it) },
) {
    val restore by E2eeRecovery.restore.collectAsState()
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var failure by remember { mutableStateOf<RecoveryFailure?>(null) }
    val scope = rememberCoroutineScope()
    val check = remember(input) {
        if (input.isBlank()) {
            null
        } else {
            val bytes = input.trim().encodeToByteArray()
            try {
                keyCheck(bytes)
            } catch (e: Exception) {
                null
            } finally {
                bytes.fill(0)
            }
        }
    }
    // the typed key is on screen
    SecureWindow()

    RecoveryScaffold(Res.string.durio_recovery_restore_title, onBack = { navController.popBackStack() }) {
        when (val state = restore) {
            is E2eeRecovery.Restore.Running -> {
                val progress = state.progress
                Text(stringResource(Res.string.durio_recovery_restore_progress, progress?.done ?: 0, progress?.total ?: 0))
                if (progress == null || progress.total == 0L) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { progress.done.toFloat() / progress.total }, modifier = Modifier.fillMaxWidth())
                }
            }
            is E2eeRecovery.Restore.Done -> {
                Text(stringResource(Res.string.durio_recovery_restore_done, state.imported), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(Res.string.durio_recovery_unauthenticated_note))
                Button(onClick = {
                    E2eeRecovery.dismissRestore()
                    navController.popBackStack()
                }) { Text(stringResource(Res.string.durio_verify_close)) }
            }
            else -> {
                if (state is E2eeRecovery.Restore.Failed && failure == null) failure = state.reason
                Text(stringResource(Res.string.durio_recovery_restore_intro))
                OutlinedTextField(
                    value = input,
                    onValueChange = {
                        input = it
                        failure = null
                    },
                    label = { Text(stringResource(Res.string.durio_recovery_input)) },
                    textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Monospace),
                    // no suggestions, no learning: it is a secret
                    keyboardOptions = KeyboardOptions(autoCorrectEnabled = false, keyboardType = KeyboardType.Password),
                    supportingText = {
                        check?.let {
                            Text(
                                stringResource(
                                    when (it) {
                                        E2eeRecoveryKeyCheck.Valid -> Res.string.durio_recovery_input_key
                                        E2eeRecoveryKeyCheck.Typo -> Res.string.durio_recovery_input_typo
                                        E2eeRecoveryKeyCheck.NotAKey -> Res.string.durio_recovery_input_passphrase
                                    }
                                )
                            )
                        }
                    },
                    isError = check == E2eeRecoveryKeyCheck.Typo,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    enabled = !busy && input.isNotBlank() && check != E2eeRecoveryKeyCheck.Typo,
                    onClick = {
                        busy = true
                        failure = null
                        E2eeRecovery.dismissRestore()
                        val bytes = input.trim().encodeToByteArray()
                        scope.launch {
                            failure = E2eeRecovery.unlockAndRestore(bytes)
                            if (failure == null) input = ""
                            busy = false
                        }
                    },
                ) { Text(stringResource(Res.string.durio_recovery_restore)) }
                if (busy) CircularProgressIndicator()
            }
        }
        failure?.let { Hint(stringResource(failureText(it)), warning = true, padded = false) }
    }
}

private fun failureText(failure: RecoveryFailure): StringResource = when (failure) {
    RecoveryFailure.Typo -> Res.string.durio_recovery_failed_typo
    RecoveryFailure.WrongKey -> Res.string.durio_recovery_failed_wrong
    RecoveryFailure.NoBackup -> Res.string.durio_recovery_failed_no_backup
    RecoveryFailure.Mismatch -> Res.string.durio_recovery_failed_mismatch
    RecoveryFailure.Network -> Res.string.durio_recovery_failed_network
    RecoveryFailure.Other -> Res.string.durio_recovery_failed_other
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RecoveryScaffold(title: StringResource, onBack: () -> Unit, content: @Composable () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back_24dp), contentDescription = stringResource(Res.string.back))
                    }
                },
            )
        }
    ) { pv ->
        Column(
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier
                .padding(pv)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) { content() }
    }
}
