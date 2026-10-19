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
