package chat.stoat.fork.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import chat.stoat.BuildConfig
import chat.stoat.api.fork.server.DiscoveredServer
import chat.stoat.api.fork.server.DiscoveryError
import chat.stoat.api.fork.server.ServerDiscovery
import chat.stoat.core.model.fork.ActiveServer
import chat.stoat.fork.account.AccountManager
import chat.stoat.fork.account.ServerHistory
import chat.stoat.persistence.KVStorage
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_server_check
import chat.stoat.shared.resources.durio_server_e2ee
import chat.stoat.shared.resources.durio_server_error_certificate
import chat.stoat.shared.resources.durio_server_error_insecure
import chat.stoat.shared.resources.durio_server_error_invalid
import chat.stoat.shared.resources.durio_server_error_no_connection
import chat.stoat.shared.resources.durio_server_error_not_stoat
import chat.stoat.shared.resources.durio_server_error_unknown
import chat.stoat.shared.resources.durio_server_foreign
import chat.stoat.shared.resources.durio_server_label
import chat.stoat.shared.resources.durio_server_no_e2ee
import chat.stoat.shared.resources.durio_server_placeholder
import chat.stoat.shared.resources.durio_server_recent
import chat.stoat.shared.resources.durio_server_version
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Server field of the login screen (plan AN-10): the user types a host such as
 * `name.duckdns.org`, discovery finds the API, and the server becomes the one login and
 * sign-up talk to. Prefilled from `BuildConfig.DEFAULT_SERVER` (empty by default) or the last
 * server used on this device.
 */
class ServerPickerViewModel(application: Application) : AndroidViewModel(application) {
    var input by mutableStateOf("")
    var checking by mutableStateOf(false)
        private set
    var error by mutableStateOf<StringResource?>(null)
        private set
    var selected by mutableStateOf(AccountManager.pendingServer)
        private set
    var recent by mutableStateOf(emptyList<String>())
        private set

    private var job: Job? = null

    init {
        viewModelScope.launch {
            recent = ServerHistory.load(KVStorage(getApplication<Application>()))
            if (selected == null) {
                val prefill = BuildConfig.DEFAULT_SERVER.ifBlank { recent.firstOrNull().orEmpty() }
                if (prefill.isNotBlank() && input.isEmpty()) {
                    input = prefill
                    check()
                }
            } else {
                input = selected?.endpoints?.api?.removePrefix("https://").orEmpty()
            }
        }
    }

    fun onInput(value: String) {
        input = value
        error = null
        // a different address has to be checked again before login
        if (selected != null) selected = null
    }

    fun check() {
        val address = input.trim()
        if (address.isEmpty()) return
        job?.cancel()
        job = viewModelScope.launch {
            checking = true
            error = null
            try {
                val server = ServerDiscovery.create(allowInsecure = BuildConfig.DEBUG)
                    .use { it.discover(address) }
                AccountManager.selectServer(server)
                selected = server
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = errorMessage(e)
            } finally {
                checking = false
            }
        }
    }

    private fun errorMessage(e: Exception): StringResource = when (e) {
        is DiscoveryError.InvalidAddress -> Res.string.durio_server_error_invalid
        is DiscoveryError.Insecure -> Res.string.durio_server_error_insecure
        is DiscoveryError.NoConnection -> Res.string.durio_server_error_no_connection
        is DiscoveryError.Certificate -> Res.string.durio_server_error_certificate
        is DiscoveryError.NotStoatServer -> Res.string.durio_server_error_not_stoat
        else -> Res.string.durio_server_error_unknown
    }

    /** Login and sign-up need a server: one checked here, or the one already active. */
    val hasServer: Boolean
        get() = selected != null || (!checking && ActiveServer.currentOrNull != null)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ServerPicker(
    modifier: Modifier = Modifier,
    viewModel: ServerPickerViewModel = viewModel(),
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = viewModel.input,
            onValueChange = viewModel::onInput,
            label = { Text(stringResource(Res.string.durio_server_label)) },
            placeholder = { Text(stringResource(Res.string.durio_server_placeholder)) },
            singleLine = true,
            isError = viewModel.error != null,
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                autoCorrectEnabled = false,
                imeAction = ImeAction.Go
            ),
            keyboardActions = KeyboardActions(onGo = { viewModel.check() }),
            trailingIcon = {
                if (viewModel.checking) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (viewModel.selected == null) {
                    TextButton(
                        onClick = viewModel::check,
                        enabled = viewModel.input.isNotBlank(),
                        modifier = Modifier.padding(end = 4.dp)
                    ) {
                        Text(stringResource(Res.string.durio_server_check))
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
        )

        viewModel.error?.let {
            Text(
                text = stringResource(it),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }

        viewModel.selected?.let { ServerSummary(it) }

        val recent = viewModel.recent.filterNot { it == viewModel.input.trim() }
        if (viewModel.selected == null && recent.isNotEmpty()) {
            Text(
                text = stringResource(Res.string.durio_server_recent),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                recent.forEach { address ->
                    SuggestionChip(
                        onClick = {
                            viewModel.onInput(address)
                            viewModel.check()
                        },
                        label = { Text(address) }
                    )
                }
            }
        }
    }
}

@Composable
private fun ServerSummary(server: DiscoveredServer) {
    val host = server.endpoints.api.substringAfter("://").substringBefore('/')
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = server.name ?: host,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
        }
        server.info.revolt?.let {
            Text(
                text = stringResource(Res.string.durio_server_version, it),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = stringResource(
                if (server.info.e2eeEnabled) Res.string.durio_server_e2ee else Res.string.durio_server_no_e2ee
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        // a dur.io server announces features.e2ee (enabled or not); others get no push (O12)
        if (server.info.features?.e2ee == null) {
            Text(
                text = stringResource(Res.string.durio_server_foreign),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary
            )
        }
    }
}
