package chat.stoat.fork.e2ee.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

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
