package com.radwan.raadpharmacy.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.customer.CustomerPhotoStore

@Composable
internal fun CustomerPhotoPreviewV211(
    uri: Uri?,
    size: Dp = 104.dp
) {
    val context = LocalContext.current
    val bitmap = remember(uri) {
        uri?.let {
            runCatching {
                context.contentResolver.openInputStream(it)?.use(BitmapFactory::decodeStream)
            }.getOrNull()
        }
    }

    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "صورة الزبون",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.42f)
                )
            }
        }
    }
}

@Composable
internal fun StoredCustomerPhotoV211(
    customerId: String,
    size: Dp = 50.dp
) {
    val context = LocalContext.current
    val file = remember(customerId) { CustomerPhotoStore(context).file(customerId) }
    val bitmap = remember(file?.absolutePath, file?.lastModified()) {
        file?.takeIf { it.isFile }?.let {
            runCatching { BitmapFactory.decodeFile(it.absolutePath) }.getOrNull()
        }
    }

    Surface(
        modifier = Modifier.size(size),
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.78f)
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "صورة الزبون",
                modifier = Modifier.fillMaxSize().clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        } else {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Rounded.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(size * 0.46f)
                )
            }
        }
    }
}
