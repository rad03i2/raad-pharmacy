package com.radwan.raadpharmacy.ui.screens

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import androidx.test.core.app.ApplicationProvider
import com.radwan.raadpharmacy.cloud.createCloudClient
import io.github.jan.supabase.annotations.SupabaseInternal
import io.github.jan.supabase.auth.MemorySessionManager
import io.github.jan.supabase.auth.MemoryCodeVerifierCache
import kotlinx.coroutines.runBlocking
import org.robolectric.Shadows.shadowOf
import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.shopping.*
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import java.io.File
import java.util.UUID
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w420dp-h1000dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShoppingScreenTest {
    @get:Rule val rule=createComposeRule()
    private val identity=ShoppingIdentity("fixture","00000000-0000-4000-8000-000000000001")
    private val rows=(1..70).map {ShoppingItem(UUID.randomUUID().toString(),"علاج رقم $it", "علبتان • تركيز 500 ملغ",false,1_792_100_000_000L,"رعد")}
    @Test fun purchasingMidListKeepsNeighbourAndSearchState() {
        var state by mutableStateOf(ShoppingUiState(identity,rows,lastSyncedAt=100))
        rule.setContent {PharmacyLedgerTheme(darkTheme=false) {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.width(380.dp).testTag("shopping-preview")) { ShoppingListContent(state,{},{},{id->state=state.copy(items=state.items.filterNot {it.id==id})},{}) }
        }}}
        rule.onNodeWithTag("shopping-list").performScrollToIndex(35)
        rule.onNodeWithContentDescription("تم شراء علاج رقم 36").performClick()
        rule.waitForIdle();rule.onNodeWithText("علاج رقم 37").assertIsDisplayed()
        rule.onNodeWithText("علاج رقم 1").assertDoesNotExist()
        save("shopping-light-list.png")
        rule.onNodeWithText("ابحث عن علاج أو ملاحظة").performTextInput("رقم 60")
        rule.onNodeWithText("علاج رقم 60").assertIsDisplayed()
        rule.onNodeWithText("نتائج البحث • 1").assertExists()
    }
    @Test fun darkNarrowOfflineListExplainsLocalPhotosAndPendingDeletes() {
        var added=false
        val photo=rows.first().copy(title="",hasPhoto=true,pending=true)
        rule.setContent {PharmacyLedgerTheme(darkTheme=true,textScale=1.15f) {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.width(320.dp).testTag("shopping-preview")) { ShoppingListContent(ShoppingUiState(identity,listOf(photo),pending=2,error="القائمة محفوظة على الهاتف"),
                {},{added=true},{},{}) }
        }}}
        rule.onNodeWithText("صورة محلية").assertIsDisplayed()
        rule.onNodeWithText("محفوظ على الهاتف • 2 بانتظار المزامنة").assertExists()
        rule.onNodeWithTag("shopping-add").performClick();assertTrue(added)
        save("shopping-dark-local-photo.png")
        rule.onNodeWithContentDescription("شرح قائمة الشراء").performClick()
        rule.onNodeWithText("قائمة تستمر معك").assertIsDisplayed()
    }
    @OptIn(SupabaseInternal::class)
    @Test fun actualAndroidDocumentPickerKeepsPhotoLocalAndAllowsPhotoOnlyMaterial() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val client=createCloudClient("http://127.0.0.1:9","fixture-key") {
            autoLoadFromStorage=false;autoSaveToStorage=false;alwaysAutoRefresh=false;autoSetupPlatform=false
            sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache()
        }
        val root=File(context.noBackupFilesDir,"composer-${UUID.randomUUID()}")
        val store=MedicineShoppingStore(context,client,root,"composer-fixture",enqueueWork={})
        var dismissed=false
        lateinit var activity: ComponentActivity
        try {
            rule.setContent { PharmacyLedgerTheme(darkTheme=false) {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val host=LocalContext.current
                activity=generateSequence(host) {(it as? ContextWrapper)?.baseContext}.filterIsInstance<ComponentActivity>().first()
                ShoppingItemComposer(store,{dismissed=true})
            } } }
            rule.onNodeWithText("حفظ وإغلاق").assertIsNotEnabled()
            val image=File(context.cacheDir,"shopping_camera/picker-fixture.jpg").apply {parentFile!!.mkdirs()}
            val bitmap=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.rgb(14,165,168))}
            image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
            rule.onNodeWithText("من الصور").performClick()
            rule.runOnIdle {
                val launched=shadowOf(activity).nextStartedActivityForResult
                assertEquals(Intent.ACTION_OPEN_DOCUMENT,launched.intent.action)
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",image)
                activity.activityResultRegistry.dispatchResult(launched.requestCode,Activity.RESULT_OK,Intent().setData(uri))
            }
            rule.waitUntil(10_000) {rule.onAllNodesWithText("الصورة محفوظة على هذا الهاتف").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("حفظ وإغلاق").assertIsEnabled()
            rule.onNodeWithText("اسم العلاج").performTextInput("باراسيتامول 500 ملغ")
            rule.onNodeWithText("الكمية أو ملاحظة (اختياري)").performTextInput("علبتان • بديل اقتصادي")
            val preview=rule.onNodeWithTag("shopping-composer").captureToImage().asAndroidBitmap()
            File("build/reports/shopping-preview").apply {mkdirs()}.resolve("shopping-add-local-photo.png").outputStream().use {preview.compress(Bitmap.CompressFormat.PNG,100,it)}
            rule.onNodeWithContentDescription("إغلاق الإضافة").performClick();assertTrue(dismissed)
            assertTrue(File(root,"drafts").listFiles().orEmpty().isEmpty())
            image.delete()
        } finally {store.local.close();root.deleteRecursively();runBlocking {client.close()}}
    }
    private fun save(name:String) {val bitmap=rule.onNodeWithTag("shopping-preview").captureToImage().asAndroidBitmap()
        File("build/reports/shopping-preview").apply {mkdirs()}.resolve(name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
}
