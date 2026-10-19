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
import androidx.compose.ui.test.junit4.StateRestorationTester
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
    @Before fun resetFileProviderSandbox() {resetProviderRoots()}
    @Test fun purchasingMidListKeepsNeighbourAndSearchState() {
        var state by mutableStateOf(ShoppingUiState(identity,rows,lastSyncedAt=100))
        rule.setContent {PharmacyLedgerTheme(darkTheme=false) {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.width(380.dp).testTag("shopping-preview")) { ShoppingListContent(state,{},{},{id->state=state.copy(items=state.items.filterNot {it.id==id})},{}) }
        }}}
        rule.onNodeWithTag("shopping-list").performScrollToIndex(35)
        hold("تم شراء علاج رقم 36")
        rule.waitForIdle();rule.onNodeWithText("علاج رقم 37").assertIsDisplayed()
        rule.onNodeWithText("علاج رقم 1").assertDoesNotExist()
        save("shopping-light-list.png")
        rule.onNodeWithContentDescription("بحث في قائمة الشراء").performClick()
        rule.onNodeWithText("ابحث عن علاج أو ملاحظة").performTextInput("رقم 60")
        rule.onNodeWithText("علاج رقم 60").assertIsDisplayed()
        rule.onNodeWithText("نتائج البحث • 1").assertExists()
    }
    @Test fun cancellingPurchaseThenScrollingDoesNotJumpBackWhenAnotherDeviceRemovesThatMaterial() {
        var state by mutableStateOf(ShoppingUiState(identity,rows,lastSyncedAt=100))
        var requested: String?=null
        rule.setContent {PharmacyLedgerTheme { ShoppingListContent(state,{},{},{requested=it},{}) }}
        rule.onNodeWithTag("shopping-list").performScrollToIndex(35)
        hold("تم شراء علاج رقم 36")
        assertEquals(rows[35].id,requested) // Controlled boundary: hold finished but this fixture leaves the row unchanged.
        rule.onNodeWithTag("shopping-list").performScrollToIndex(60)
        rule.runOnIdle {state=state.copy(items=state.items.filterNot {it.id==requested})}
        rule.waitForIdle()
        rule.onNodeWithText("علاج رقم 61").assertIsDisplayed()
        rule.onNodeWithText("علاج رقم 37").assertDoesNotExist()
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
    @Test @Config(sdk=[26,35],qualifiers="w420dp-h1000dp-mdpi") fun actualAndroidGalleryPickerKeepsPhotoLocalAndAllowsPhotoOnlyMaterial() {
        val context=ApplicationProvider.getApplicationContext<Context>()
        val client=createCloudClient("http://127.0.0.1:9","fixture-key") {
            autoLoadFromStorage=false;autoSaveToStorage=false;alwaysAutoRefresh=false;autoSetupPlatform=false
            sessionManager=MemorySessionManager();codeVerifierCache=MemoryCodeVerifierCache()
        }
        val root=File(context.noBackupFilesDir,"composer-${UUID.randomUUID()}")
        val store=MedicineShoppingStore(context,client,root,"composer-fixture",enqueueWork={})
        var dismissed=false
        lateinit var activity: ComponentActivity
        val restoration=StateRestorationTester(rule)
        try {
            restoration.setContent { PharmacyLedgerTheme(darkTheme=false) {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                val host=LocalContext.current
                activity=generateSequence(host) {(it as? ContextWrapper)?.baseContext}.filterIsInstance<ComponentActivity>().first()
                ShoppingItemComposer(store,{dismissed=true})
            } } }
            rule.onNodeWithText("حفظ وإغلاق").assertIsNotEnabled()
            val image=File(context.cacheDir,"shopping_camera/picker-fixture.jpg").apply {parentFile!!.mkdirs()}
            val bitmap=Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888).apply {eraseColor(android.graphics.Color.rgb(14,165,168))}
            image.outputStream().use {bitmap.compress(Bitmap.CompressFormat.JPEG,90,it)};bitmap.recycle()
            rule.onNodeWithText("اسم العلاج").performTextInput("باراسيتامول 500 ملغ")
            rule.onNodeWithText("من الصور").performClick()
            var requestCode: Int?=null
            rule.waitUntil(10_000) {
                rule.runOnIdle {
                    val launched=shadowOf(activity).nextStartedActivityForResult
                    if(launched!=null) {
                        assertEquals(ShoppingGalleryContract().createIntent(activity,Unit).action,launched.intent.action)
                        assertNotEquals(Intent.ACTION_OPEN_DOCUMENT,launched.intent.action)
                        requestCode=launched.requestCode
                    }
                };requestCode!=null
            }
            restoration.emulateSavedInstanceStateRestore()
            rule.onNodeWithText("باراسيتامول 500 ملغ").assertExists()
            rule.runOnIdle {
                val uri=FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",image)
                activity.activityResultRegistry.dispatchResult(requestCode!!,Activity.RESULT_OK,Intent().setData(uri))
            }
            rule.waitUntil(10_000) {rule.onAllNodesWithText("الصورة محفوظة على هذا الهاتف").fetchSemanticsNodes().isNotEmpty()}
            rule.onNodeWithText("حفظ وإغلاق").assertIsEnabled()
            rule.onNodeWithText("باراسيتامول 500 ملغ").assertExists()
            rule.onNodeWithText("الكمية أو ملاحظة (اختياري)").performTextInput("علبتان • بديل اقتصادي")
            if(android.os.Build.VERSION.SDK_INT>=28) {
            val preview=rule.onNodeWithTag("shopping-composer").captureToImage().asAndroidBitmap()
            File("build/reports/shopping-preview").apply {mkdirs()}.resolve("shopping-add-local-photo.png").outputStream().use {preview.compress(Bitmap.CompressFormat.PNG,100,it)}
            }
            rule.onNodeWithContentDescription("إغلاق الإضافة").performClick();assertTrue(dismissed)
            assertTrue(File(root,"drafts").listFiles().orEmpty().isEmpty())
            image.delete()
        } finally {store.local.close();store.suppliers.close();root.deleteRecursively();runBlocking {client.close()}}
    }
    @Test @Config(sdk=[26,35],qualifiers="w420dp-h1000dp-mdpi")
    fun photoResultWaitsForTheSameDraftWhenThePinGateRemovesTheComposer() {
        var visible by mutableStateOf(true)
        var returned: Uri?=null
        lateinit var activity: ComponentActivity
        val id=UUID.randomUUID().toString()
        rule.setContent {
            val host=LocalContext.current
            activity=generateSequence(host) {(it as? ContextWrapper)?.baseContext}.filterIsInstance<ComponentActivity>().first()
            PharmacyLedgerTheme {
                if(visible) {
                    val gallery=rememberShoppingGalleryLaunch(id) {returned=it}
                    androidx.compose.material3.Button(onClick=gallery) {androidx.compose.material3.Text("open-gallery")}
                } else androidx.compose.material3.Text("PIN required")
            }
        }
        rule.onNodeWithText("open-gallery").performClick()
        var code=0
        rule.runOnIdle {code=shadowOf(activity).nextStartedActivityForResult.requestCode;visible=false}
        rule.onNodeWithText("PIN required").assertExists()
        val uri=Uri.parse("content://fixture/pending-photo")
        rule.runOnIdle {activity.activityResultRegistry.dispatchResult(code,Activity.RESULT_OK,Intent().setData(uri))}
        assertNull(returned) // The lock is respected; the image callback has no hidden UI bypass.
        rule.runOnIdle {visible=true}
        rule.waitUntil {returned!=null};assertEquals(uri,returned)
    }
    @Test fun cardsHaveLargePhotosAndSameDetailsAndListRemainsDefaultWithIndependentAnchors() {
        var state by mutableStateOf(ShoppingUiState(identity,rows,lastSyncedAt=100))
        rule.setContent {PharmacyLedgerTheme {CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            Box(Modifier.width(380.dp).testTag("shopping-preview")) {ShoppingListContent(state,{},{},{id->state=state.copy(items=state.items.filterNot {it.id==id})},{})}
        }}}
        rule.onNodeWithTag("shopping-list").assertExists()
        rule.onNodeWithTag("shopping-list").performScrollToIndex(35)
        rule.onNodeWithContentDescription("عرض البطاقات").performClick()
        rule.onNodeWithTag("shopping-grid").performScrollToIndex(40)
        rule.onNodeWithText("علاج رقم 41").assertIsDisplayed()
        hold("تم شراء علاج رقم 41")
        rule.onNodeWithText("علاج رقم 42").assertIsDisplayed()
        rule.onNodeWithContentDescription("عرض القائمة").performClick()
        rule.onNodeWithText("علاج رقم 36").assertIsDisplayed()
        rule.onNodeWithText("مشتريات الصيدلية").assertDoesNotExist()
        rule.onNodeWithText("ما لم تجده اليوم، يبقى للجولة القادمة").assertDoesNotExist()
        rule.onNodeWithContentDescription("عرض البطاقات").performClick()
        save("shopping-grid-light.png")
    }
    @Test fun sessionRefreshKeepsNavigationAndActiveComposerStateAndSignOutStillRemovesIt() {
        var phase by mutableIntStateOf(0)
        var disposed=0
        rule.setContent {
            val allow=phase!=3
            // Read each phase in the parent, as the real auth flow does.
            key("auth-wrapper") {com.radwan.raadpharmacy.cloud.StableCloudSessionContent(allow,content={
                var form by remember {mutableStateOf("")}
                DisposableEffect(Unit) {onDispose {disposed++}}
                androidx.compose.material3.OutlinedTextField(form,{form=it},Modifier.testTag("retained-form"))
            },fallback={androidx.compose.material3.Text("login")})}
        }
        rule.onNodeWithTag("retained-form").performTextInput("مسودة علاج أثناء المعرض")
        repeat(3) {n ->rule.runOnIdle {phase=n};rule.onNodeWithText("مسودة علاج أثناء المعرض").assertExists();assertEquals(0,disposed)}
        rule.runOnIdle {phase=3};rule.onNodeWithText("login").assertExists();assertEquals(1,disposed)
    }
    @Test fun shortPressAndCancelledHoldNeverPurchaseButFullSecondCommitsOnce() {
        var count=0
        rule.setContent {PharmacyLedgerTheme {HoldToPurchase("اختبار") {count++}}}
        rule.mainClock.autoAdvance=false
        val button=rule.onNodeWithContentDescription("تم شراء اختبار")
        button.performTouchInput {down(center)}
        rule.mainClock.advanceTimeBy(600)
        rule.onRoot().performTouchInput {up()}
        rule.mainClock.advanceTimeBy(200)
        assertEquals(0,count)
        button.performTouchInput {down(center)}
        rule.mainClock.advanceTimeBy(1100)
        assertEquals(1,count)
        rule.mainClock.advanceTimeBy(2000)
        assertEquals(1,count)
        rule.onRoot().performTouchInput {up()}
        rule.mainClock.autoAdvance=true
    }
    @Test fun recentlyDeletedMaterialHasAnActualRestoreAction() {
        var restored=""
        val trash=ShoppingTrash(rows.first(),UUID.randomUUID().toString(),System.currentTimeMillis()+86_000_000)
        rule.setContent {PharmacyLedgerTheme {ShoppingListContent(ShoppingUiState(identity,emptyList(),trash=listOf(trash)),{},{},{},{},onRestore={restored=it})}}
        rule.onNodeWithContentDescription("المحذوفة حديثاً").performClick()
        rule.onNodeWithText("استعادة").performClick()
        assertEquals(rows.first().id,restored)
    }
    private fun hold(description: String) {
        rule.mainClock.autoAdvance=false
        rule.onNodeWithContentDescription(description).performTouchInput {down(center)}
        rule.mainClock.advanceTimeBy(1100)
        rule.onRoot().performTouchInput {up()}
        rule.mainClock.autoAdvance=true
        rule.waitForIdle()
    }
    private fun save(name:String) {val bitmap=rule.onNodeWithTag("shopping-preview").captureToImage().asAndroidBitmap()
        File("build/reports/shopping-preview").apply {mkdirs()}.resolve(name).outputStream().use {bitmap.compress(Bitmap.CompressFormat.PNG,100,it)}}
    private fun resetProviderRoots() {
        // AndroidX caches absolute roots; Robolectric assigns a different data directory to each test.
        val field=FileProvider::class.java.getDeclaredField("sCache").apply {isAccessible=true}
        (field.get(null) as MutableMap<*,*>).clear()
    }

}
