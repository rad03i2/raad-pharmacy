package com.radwan.raadpharmacy.shopping

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.radwan.raadpharmacy.MainActivity
import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@Serializable
internal data class SharedShoppingDraft(val request: String,val ids: List<String>,val quick: Boolean,val title: String="",val quantity: String="",val caption: String="")
/** Copies incoming content while the transient URI grant is valid; never persists an external URI or uploads image bytes. */
internal class ShoppingShareInbox(private val context: Context) {
    private val root=File(context.noBackupFilesDir,"medicine-shopping/share-inbox")
    private fun file(id: String)=File(root,"${UUID.fromString(id)}.json")
    fun read(id: String): SharedShoppingDraft=Json.decodeFromString(file(id).readText())
    fun exists(id: String)=file(id).isFile
    fun write(draft: SharedShoppingDraft) {
        root.mkdirs();val destination=file(draft.request);val writing=File(root,"${draft.request}.writing")
        FileOutputStream(writing).use {it.write(Json.encodeToString(draft).toByteArray());it.fd.sync()}
        check(writing.renameTo(destination))
    }
    fun discard(id: String) {val draft=read(id);draft.ids.forEach {ShoppingPhotos(context).discardDraft(it)};file(id).delete()}
    @Suppress("DEPRECATION")
    fun receive(intent: Intent,request: String): SharedShoppingDraft {
        if(exists(request)) return read(request)
        require(intent.type?.startsWith("image/")==true && intent.action in setOf(Intent.ACTION_SEND,Intent.ACTION_SEND_MULTIPLE)) {"المشاركة تدعم الصور فقط."}
        val uris=if(intent.action==Intent.ACTION_SEND_MULTIPLE) intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM).orEmpty()
            else listOfNotNull(intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))
        val sources=(uris.ifEmpty {intent.clipData?.let {clip -> (0 until clip.itemCount).mapNotNull {clip.getItemAt(it).uri}}.orEmpty()}).distinct()
        require(sources.isNotEmpty() && sources.size<=20) {"شارك من صورة واحدة إلى 20 صورة في المرة الواحدة."}
        require(sources.all {it.scheme=="content"}) {"تعذر قراءة الصور المشتركة؛ شاركها من المعرض أو مزوّد ملفات."}
        val ids=sources.mapIndexed {index,_ -> UUID.nameUUIDFromBytes("$request:$index".toByteArray()).toString()}
        val photos=ShoppingPhotos(context)
        try {sources.forEachIndexed {index,uri -> photos.import(ids[index],uri)}
            val result=SharedShoppingDraft(request,ids,intent.component?.className?.endsWith("ShoppingQuickShare")==true,
                caption=intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty().take(800))
            write(result);return result
        } catch(e: Exception) {ids.forEach {photos.discardDraft(it)};throw e}
    }
}

class ShoppingShareActivity: FragmentActivity() {
    private var request=UUID.randomUUID().toString()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        request=savedInstanceState?.getString("shopping-request")?:request
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {ShoppingShareInbox(applicationContext).receive(intent,request)}
                startActivity(Intent(this@ShoppingShareActivity,MainActivity::class.java)
                    .putExtra(MainActivity.EXTRA_SHOPPING_SHARE,request)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                finish()
            } catch(cancelled: CancellationException) {throw cancelled}
            catch(e: Exception) {
                android.app.AlertDialog.Builder(this@ShoppingShareActivity).setTitle("تعذرت إضافة الصورة")
                    .setMessage(e.message?:"لم تُحفظ الصورة. أعد المشاركة من التطبيق المصدر.")
                    .setPositiveButton("حسناً") {_,_->finish()}.setOnCancelListener {finish()}.show()
            }
        }
    }
    override fun onSaveInstanceState(outState: Bundle) {outState.putString("shopping-request",request);super.onSaveInstanceState(outState)}
}
