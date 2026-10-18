package chat.stoat.fork.e2ee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import chat.stoat.shared.resources.Res
import chat.stoat.shared.resources.durio_e2ee_beta
import org.jetbrains.compose.resources.stringResource

/**
 * "beta" on every E2EE entry point (O8: no external audit yet): chat badge and rows, the enable
 * dialog, the settings entry and screens, the first prompts of a device, verification.
 */
@Composable
internal fun BetaLabel() {
    Text(
        text = stringResource(Res.string.durio_e2ee_beta),
        fontSize = 9.sp,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = Modifier
            .testTag(BETA_TAG)
            .clip(MaterialTheme.shapes.extraSmall)
            .background(MaterialTheme.colorScheme.tertiaryContainer)
            .padding(horizontal = 3.dp)
    )
}

internal const val BETA_TAG = "durio-e2ee-beta"

/** A title with the beta label after it. */
@Composable
internal fun BetaTitle(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(text)
        BetaLabel()
    }
}

/** A line of explanation or warning on the E2EE screens (AN-30..32, AN-42). */
@Composable
internal fun Hint(text: String, warning: Boolean, padded: Boolean = true) {
    Box(
        Modifier
            .then(if (padded) Modifier.padding(horizontal = 16.dp, vertical = 4.dp) else Modifier)
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(if (warning) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceContainer)
            .padding(12.dp)
    ) {
        Text(text, style = MaterialTheme.typography.bodySmall)
    }
}
