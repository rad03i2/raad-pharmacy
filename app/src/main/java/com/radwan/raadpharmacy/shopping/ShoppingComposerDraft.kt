package com.radwan.raadpharmacy.shopping

import android.content.Context
import android.media.AudioManager
import android.util.AtomicFile
import java.io.File
import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
internal data class ShoppingComposerDraft(val owner: String,val pharmacy: String,val id: String,
    val title: String="",val note: String="",val uri: String?=null)

/** Separate from financial form drafts; scoped to the signed-in pharmacy, local and never uploaded. */
internal class ShoppingComposerDraftStore(root: File) {
    private val file=AtomicFile(File(root.apply {mkdirs()},"composer.json"))
    @Synchronized fun load(identity: ShoppingIdentity?): ShoppingComposerDraft? {
        identity ?: return null
        return runCatching {
            Json.decodeFromString<ShoppingComposerDraft>(file.openRead().bufferedReader().use {it.readText()}).also {
                UUID.fromString(it.id)
                require(it.title.length<=240 && it.note.length<=1000 && (it.uri?.length ?: 0)<=16_384)
            }
        }
            .getOrNull()?.takeIf {it.owner==identity.owner && it.pharmacy==identity.pharmacy}
    }
    @Synchronized fun save(value: ShoppingComposerDraft) {
        // Form autosaves must not drop an image result awaiting import after process recreation.
        val old=load(ShoppingIdentity(value.owner,value.pharmacy))
        val row=if(value.uri==null && old?.id==value.id) value.copy(uri=old.uri) else value
        write(row)
    }
    @Synchronized fun imported(identity: ShoppingIdentity,id: String) {
        load(identity)?.takeIf {it.id==id}?.let {write(it.copy(uri=null))}
    }
    private fun write(value: ShoppingComposerDraft) {
        val output=file.startWrite()
        try {output.write(Json.encodeToString(value).toByteArray());file.finishWrite(output)}
        catch(error: Exception) {file.failWrite(output);throw error}
    }
    @Synchronized fun clear(id: String) {
        val held=runCatching {Json.decodeFromString<ShoppingComposerDraft>(file.openRead().bufferedReader().use {it.readText()})}.getOrNull()
        if(held?.id==id) file.delete()
    }
}

/** A quiet system UI click: no audio focus, no notification channel, no interruption of ledger sounds. */
internal object ShoppingPurchaseFeedback {
    fun play(context: Context) {runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager)?.playSoundEffect(AudioManager.FX_KEY_CLICK,0.18f)
    }}
}
