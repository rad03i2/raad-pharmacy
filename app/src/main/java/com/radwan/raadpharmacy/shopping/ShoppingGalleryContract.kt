package com.radwan.raadpharmacy.shopping

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts

/** Native/backported photo gallery first; image providers on old phones, never the generic document picker. */
internal class ShoppingGalleryContract : ActivityResultContract<Unit,Uri?>() {
    override fun createIntent(context: Context,input: Unit): Intent =
        if(ActivityResultContracts.PickVisualMedia.isPhotoPickerAvailable(context))
            ActivityResultContracts.PickVisualMedia().createIntent(context,PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
    override fun parseResult(resultCode: Int,intent: Intent?): Uri? = if(resultCode==Activity.RESULT_OK) intent?.data else null
}

/** The PIN screen may intentionally remove the composer. A draft UUID, rather than a UI-owned
 * random registration key, lets the registry deliver a pending result after unlocking/recreation. */
@androidx.compose.runtime.Composable
internal fun rememberShoppingGalleryLaunch(id: String,onResult: (Uri?)->Unit): ()->Unit {
    val registry=checkNotNull(androidx.activity.compose.LocalActivityResultRegistryOwner.current).activityResultRegistry
    val latest=androidx.compose.runtime.rememberUpdatedState(onResult)
    val state=androidx.compose.runtime.remember(registry,id) {
        androidx.compose.runtime.mutableStateOf<androidx.activity.result.ActivityResultLauncher<Unit>?>(null)
    }
    androidx.compose.runtime.DisposableEffect(registry,id) {
        val active=registry.register("raad-shopping-gallery:$id",ShoppingGalleryContract()) {latest.value(it)}
        state.value=active
        onDispose {active.unregister();state.value=null}
    }
    return {checkNotNull(state.value) {"انتظر اكتمال فتح شاشة الإضافة ثم أعد المحاولة."}.launch(Unit)}
}
