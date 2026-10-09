package com.radwan.raadpharmacy.ui.components

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.radwan.raadpharmacy.cloud.EntryActorStore

/** Small, passive author label below the amount. Never substitutes the editor for the creator. */
@Composable
fun EntryActorCaption(entryId: String, color: Color? = null) {
    val context = LocalContext.current
    val store = remember(context) { EntryActorStore.get(context) }
    val authorIds by store.authorIds.collectAsState()
    val displayNames by store.displayNames.collectAsState()
    val authorName = authorIds[entryId]?.let { displayNames[it] }
    if (!authorName.isNullOrBlank()) {
        Text(
            text = authorName,
            style = MaterialTheme.typography.labelSmall,
            color = color ?: MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
