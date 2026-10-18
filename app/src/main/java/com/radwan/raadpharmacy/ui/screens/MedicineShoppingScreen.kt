package com.radwan.raadpharmacy.ui.screens

import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.onLongClick
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.radwan.raadpharmacy.shopping.*
import com.radwan.raadpharmacy.ui.components.ScreenTopBar
import com.radwan.raadpharmacy.ui.components.rememberLedgerFlingBehavior
import kotlinx.coroutines.*
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

@Composable
fun MedicineShoppingScreen(onBack: () -> Unit) {
    val context=LocalContext.current
    val store=remember(context) { MedicineShoppingStore.get(context) }
    val state by store.state.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    DisposableEffect(store) { store.visible=true; store.start(); store.request(); onDispose { store.visible=false } }
    if(adding) ShoppingItemComposer(store, onDismiss={ adding=false })
    LaunchedEffect(store) { while(true) { delay(15_000);store.refreshLocal() } }
    if(error!=null) AlertDialog(onDismissRequest={error=null},title={Text("لم تكتمل العملية")},text={Text(error.orEmpty())},
        confirmButton={TextButton(onClick={error=null}){Text("حسناً")}})
    ShoppingListContent(state,onBack,{adding=true},{id -> if(!busy) {busy=true;scope.launch {
        try {store.purchase(id)} catch(cancelled: CancellationException) {throw cancelled}
        catch(_: Exception) {error="تعذر حفظ إتمام الشراء. حاول مجدداً."} finally {busy=false}
    }}},{store.request()},photoFile={item -> state.identity?.let { store.photos.file(it.pharmacy,item.id) }},
        onRestore={id -> scope.launch {try {store.restore(id)} catch(cancelled: CancellationException) {throw cancelled}
            catch(e: Exception) {error=e.message ?: "تعذرت الاستعادة."}}})
}

@Composable
internal fun ShoppingListContent(state: ShoppingUiState,onBack: ()->Unit,onAdd: ()->Unit,onPurchase: (String)->Unit,
    onRefresh: ()->Unit,photoFile: (ShoppingItem)->File? = { null },onRestore: (String)->Unit = {}) {
    val colors=MaterialTheme.colorScheme
    var query by rememberSaveable { mutableStateOf("") }
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var trashOpen by rememberSaveable { mutableStateOf(false) }
    var help by rememberSaveable { mutableStateOf(false) }
    var viewing by rememberSaveable { mutableStateOf<String?>(null) }
    val scroll=rememberLazyListState()
    val shown=remember(state.items,query) { state.items.filter { query.isBlank() || it.title.contains(query,true) || it.note.contains(query,true) } }
    if(help) AlertDialog(onDismissRequest={help=false},icon={Icon(Icons.Rounded.ShoppingBag,null)},title={Text("قائمة تستمر معك")},
        text={Text("أضف اسم العلاج أو صورته، ويمكنك إضافة الكمية أو ملاحظة. المواد غير المشتراة تبقى تلقائياً مع الإضافات الجديدة.\n\nالنصوص مشتركة بين حسابات الصيدلية. الصورة تبقى على جهاز إضافتها فقط ولا تُرفع للسحابة؛ أضف اسماً معها ليسهل تمييزها على بقية الأجهزة.\n\nاضغط مطولاً على علامة الصح لمدة ثانية لإتمام الشراء. يمكنك استعادة المادة من المحذوفة حديثاً خلال 24 ساعة؛ بعدها تُحذف نهائياً. الأجهزة غير المتصلة تتلقى التغيير عند اتصالها. الحذف من إصدار أقدم قد يكون نهائياً.")},
        confirmButton={TextButton(onClick={help=false}){Text("فهمت")}})
    if(trashOpen) AlertDialog(onDismissRequest={trashOpen=false},title={Text("المحذوفة حديثاً")},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("مهلة الاستعادة 24 ساعة من الحذف، ويجب تأكيد الاستعادة على السحابة قبل انتهائها. بعدها تُحذف المادة نهائياً؛ تنظيف الصورة المحلية يتم عند تشغيل الجهاز.",style=MaterialTheme.typography.bodySmall)
            if(state.trash.isEmpty()) Text("لا توجد مواد قابلة للاستعادة")
            LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(state.trash,key={it.item.id}) {held ->
                    Surface(shape=RoundedCornerShape(16.dp),color=colors.surfaceContainerLow) {
                        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(held.item.title.ifBlank {"علاج بصورة"},style=MaterialTheme.typography.titleSmall)
                                val minutes=((held.expiresAt-System.currentTimeMillis())/60_000).coerceAtLeast(0)
                                Text(if(minutes>=60) "متبقي ${minutes/60} ساعة" else "متبقي $minutes دقيقة",style=MaterialTheme.typography.labelSmall)
                            }
                            TextButton(onClick={onRestore(held.item.id)}) {Text("استعادة")}
                        }
                    }
                }
            }
        }},confirmButton={TextButton(onClick={trashOpen=false}) {Text("إغلاق")}})
    val viewed=state.items.firstOrNull { it.id==viewing }
    if(viewed!=null) AlertDialog(onDismissRequest={viewing=null},title={Text(viewed.title.ifBlank { "صورة العلاج" })},
        text={Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            ShoppingPhoto(photoFile(viewed),viewed.hasPhoto,Modifier.fillMaxWidth().height(300.dp),full=true)
            if(viewed.note.isNotBlank()) Text(viewed.note)
            if(viewed.hasPhoto) Text("الصورة محفوظة محلياً على جهاز إضافتها فقط.",style=MaterialTheme.typography.bodySmall)
        }},confirmButton={TextButton(onClick={viewing=null}){Text("إغلاق")}})
    Scaffold(topBar={ ScreenTopBar("قائمة الشراء",onBack,actions={
        IconButton(onClick={searchOpen=!searchOpen;if(!searchOpen) query=""}) { Icon(if(searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,"بحث في قائمة الشراء") }
        IconButton(onClick={trashOpen=true}) { BadgedBox(badge={if(state.trash.isNotEmpty()) Badge {Text(state.trash.size.toString())}}) {
            Icon(Icons.Rounded.History,"المحذوفة حديثاً") } }
        IconButton(onClick={help=true}) { Icon(Icons.Rounded.Info,"شرح قائمة الشراء") }
    }) },floatingActionButton={ ExtendedFloatingActionButton(onClick=onAdd,modifier=Modifier.testTag("shopping-add").semantics {contentDescription="إضافة علاج"},icon={Icon(Icons.Rounded.Add,null)},
        text={Text("إضافة علاج")},expanded=true,containerColor=colors.primary,contentColor=colors.onPrimary) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Surface(shape=RoundedCornerShape(24.dp),color=colors.primaryContainer) {
                Row(Modifier.fillMaxWidth().background(Brush.linearGradient(listOf(colors.primaryContainer,colors.secondaryContainer)))
                    .padding(14.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text("مشتريات الصيدلية",style=MaterialTheme.typography.labelLarge,color=colors.onPrimaryContainer)
                        Row(verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text(state.items.size.toString(),style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.Bold,color=colors.onPrimaryContainer)
                            Text("مادة مطلوبة",modifier=Modifier.padding(bottom=7.dp),color=colors.onPrimaryContainer)
                        }
                        Text("ما لم تجده اليوم، يبقى للجولة القادمة",style=MaterialTheme.typography.bodySmall,color=colors.onPrimaryContainer)
                    }
                    Surface(shape=CircleShape,color=colors.surface.copy(alpha=.6f)) {
                        Icon(Icons.Rounded.ShoppingBag,null,modifier=Modifier.padding(12.dp).size(26.dp),tint=colors.primary)
                    }
                }
            }
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Icon(when {state.pending>0 -> Icons.Rounded.CloudUpload; state.error!=null -> Icons.Rounded.CloudOff; else -> Icons.Rounded.CloudDone},
                    null,tint=if(state.error!=null) colors.error else colors.secondary,modifier=Modifier.size(20.dp))
                Text(when {
                    state.identity==null -> "جارٍ فتح قائمة الصيدلية…"
                    state.pending>0 -> "محفوظ على الهاتف • ${state.pending} بانتظار المزامنة"
                    state.syncing -> "جارٍ تحديث القائمة…"
                    state.error!=null -> "القائمة محفوظة • بانتظار الاتصال"
                    state.lastSyncedAt==0L -> "القائمة المحفوظة • جارٍ التحقق من السحابة"
                    else -> "القائمة مشتركة ومحدّثة"
                },style=MaterialTheme.typography.labelMedium,modifier=Modifier.weight(1f))
                IconButton(onClick=onRefresh,enabled=!state.syncing,modifier=Modifier.size(40.dp)) { Icon(Icons.Rounded.Refresh,"تحديث قائمة الشراء") }
            }
            if(state.error!=null) Text(state.error,style=MaterialTheme.typography.bodySmall,color=colors.error)
            if(searchOpen) OutlinedTextField(query,{query=it},modifier=Modifier.fillMaxWidth(),singleLine=true,
                placeholder={Text("ابحث عن علاج أو ملاحظة")},leadingIcon={Icon(Icons.Rounded.Search,null)},
                trailingIcon={if(query.isNotEmpty()) IconButton(onClick={query=""}) { Icon(Icons.Rounded.Close,"مسح البحث") }},
                shape=RoundedCornerShape(18.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search))
            if(shown.isEmpty()) Column(Modifier.weight(1f).fillMaxWidth().padding(bottom=80.dp),
                verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(if(query.isBlank()) Icons.Rounded.Inventory2 else Icons.Rounded.SearchOff,null,tint=colors.secondary,modifier=Modifier.size(52.dp))
                Spacer(Modifier.height(14.dp))
                Text(if(query.isBlank()) "قائمة خفيفة، وجولة مرتّبة" else "لا توجد مادة بهذا البحث",style=MaterialTheme.typography.titleMedium)
                Text(if(query.isBlank()) "أضف العلاجات المطلوبة بالاسم أو الصورة" else "جرّب اسماً آخر أو امسح البحث",style=MaterialTheme.typography.bodySmall)
            } else {
                Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                    Text(if(query.isBlank()) "المواد المطلوبة" else "نتائج البحث • ${shown.size}",style=MaterialTheme.typography.titleSmall,modifier=Modifier.weight(1f))
                    Text("اضغط ✓ مطولاً لثانية",style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                }
                LazyColumn(state=scroll,flingBehavior=rememberLedgerFlingBehavior(),modifier=Modifier.weight(1f).testTag("shopping-list"),
                    contentPadding=PaddingValues(bottom=100.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    items(shown,key={it.id},contentType={"shopping-item"}) { item ->
                        // Stable item keys keep the neighbouring row in view, including a purchase confirmed later.
                        ShoppingItemCard(item,photoFile(item),onPhoto={viewing=item.id},onPurchase={onPurchase(item.id)})
                    }
                }
            }
        }
    }
}

@Composable
private fun ShoppingItemCard(item: ShoppingItem,photo: File?,onPhoto: ()->Unit,onPurchase: ()->Unit) {
    val colors=MaterialTheme.colorScheme
    Surface(shape=RoundedCornerShape(20.dp),color=colors.surface,border=BorderStroke(1.dp,colors.outlineVariant),modifier=Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            ShoppingPhoto(photo,item.hasPhoto,Modifier.size(62.dp).clickable(onClick=onPhoto))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(item.title.ifBlank { "علاج بصورة • ${item.id.takeLast(6)}" },style=MaterialTheme.typography.titleSmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                if(item.note.isNotBlank()) Text(item.note,style=MaterialTheme.typography.bodySmall,maxLines=2,overflow=TextOverflow.Ellipsis)
                Text(if(item.pending) "محفوظ • بانتظار الرفع" else "${item.createdBy} • ${shoppingDate(item.createdAt)}",
                    style=MaterialTheme.typography.labelSmall,color=colors.onSurfaceVariant)
                if(item.hasPhoto) Text("صورة محلية",style=MaterialTheme.typography.labelSmall,color=colors.secondary)
                if(System.currentTimeMillis()-item.createdAt>7L*24*60*60*1000) Text("متبقية من جولة سابقة",style=MaterialTheme.typography.labelSmall,color=colors.primary)
            }
            HoldToPurchase(item.title.ifBlank { "العلاج ${item.id.takeLast(6)}" },onPurchase)

        }
    }
}

/** No tap handler: release, scrolling, leaving the screen or disposal cancels the 1-second hold. */
@Composable
internal fun HoldToPurchase(title: String,onPurchase: ()->Unit) {
    val progress=remember {Animatable(0f)}
    val animationScope=rememberCoroutineScope()
    val latest by rememberUpdatedState(onPurchase)
    val colors=MaterialTheme.colorScheme
    Surface(shape=CircleShape,color=colors.secondaryContainer,modifier=Modifier.size(48.dp)
        .graphicsLayer {scaleX=1f+progress.value*.06f;scaleY=scaleX}
        .semantics {contentDescription="تم شراء $title";role=Role.Button
            onLongClick("تأكيد الشراء") {latest();true} }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down=awaitFirstDown(requireUnconsumed=false)
                down.consume()
                val job=animationScope.launch {progress.snapTo(0f);progress.animateTo(1f,tween(1000,easing=LinearEasing));latest()}
                val up=waitForUpOrCancellation()
                job.cancel();animationScope.launch {progress.snapTo(0f)}
                up?.consume()
            }
        }) {
        Box(contentAlignment=Alignment.Center) {
            if(progress.value>0f) CircularProgressIndicator(progress={progress.value},modifier=Modifier.fillMaxSize().padding(2.dp),strokeWidth=3.dp,color=colors.primary)
            Icon(Icons.Rounded.Check,null,tint=colors.onSecondaryContainer)
        }
    }
}

@Composable
private fun ShoppingPhoto(file: File?,hasPhoto: Boolean,modifier: Modifier,full: Boolean=false) {
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null,file?.path,full) {
        value=withContext(Dispatchers.IO) {
            runCatching {
                if(file?.isFile!=true) return@runCatching null
                val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }; BitmapFactory.decodeFile(file.path,bounds)
                var sample=1; val side=if(full) 1600 else 160
                while(maxOf(bounds.outWidth,bounds.outHeight)/sample>side*2) sample*=2
                BitmapFactory.decodeFile(file.path,BitmapFactory.Options().apply { inSampleSize=sample })?.asImageBitmap()
            }.getOrNull()
        }
    }
    Surface(modifier,shape=RoundedCornerShape(14.dp),color=MaterialTheme.colorScheme.surfaceVariant) {
        val image=bitmap
        if(image!=null) Image(image,"صورة العلاج المحلية",Modifier.fillMaxSize(),contentScale=if(full) ContentScale.Fit else ContentScale.Crop)
        else Column(Modifier.fillMaxSize().padding(5.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.Center) {
            Icon(if(hasPhoto) Icons.Rounded.Photo else Icons.Rounded.Medication,null,tint=MaterialTheme.colorScheme.primary,modifier=Modifier.size(28.dp))
            if(full && hasPhoto) Text("الصورة على جهاز إضافتها فقط",style=MaterialTheme.typography.bodySmall)
        }
    }
}

private fun shoppingDate(time: Long): String = DateTimeFormatter.ofPattern("d / M").withZone(ZoneId.of("Asia/Baghdad")).format(Instant.ofEpochMilli(time))

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShoppingItemComposer(store: MedicineShoppingStore,onDismiss: ()->Unit) {
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    var id by rememberSaveable { mutableStateOf(UUID.randomUUID().toString()) }
    var title by rememberSaveable { mutableStateOf("") }
    var note by rememberSaveable { mutableStateOf("") }
    var photo by rememberSaveable { mutableStateOf(false) }
    var imageRevision by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPath by rememberSaveable { mutableStateOf<String?>(null) }
    fun importPhoto(uri: Uri) {
        busy=true; error=null
        scope.launch {
            try { withContext(Dispatchers.IO) { store.photos.import(id,uri) }; photo=true; imageRevision++ }
            catch(cancelled: CancellationException) { throw cancelled }
            catch(errorValue: Exception) { error=errorValue.message ?: "تعذر حفظ الصورة؛ اختر صورة أخرى." }
            finally { busy=false }
        }
    }
    val gallery=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if(uri!=null) importPhoto(uri) }
    val camera=rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { success ->
        val path=cameraPath
        if(success && path!=null) importPhoto(Uri.fromFile(File(path)))
        // The camera file is temporary and remains local; delete it after import in a separate coroutine.
        if(path!=null) scope.launch { while(busy) delay(100); withContext(Dispatchers.IO) { File(path).delete() } }
        cameraPath=null
    }
    fun save(another: Boolean) {
        if(busy) return
        busy=true; error=null
        scope.launch {
            try {
                store.add(id,title,note,photo)
                if(another) { id=UUID.randomUUID().toString(); title=""; note=""; photo=false; imageRevision++ }
                else onDismiss()
            } catch(cancelled: CancellationException) { throw cancelled }
            catch(e: Exception) { error=e.message ?: "تعذر حفظ العلاج؛ أعد المحاولة." }
            finally { busy=false }
        }
    }
    ModalBottomSheet(onDismissRequest={ if(!busy) { store.photos.discardDraft(id); onDismiss() } },
        sheetState=rememberModalBottomSheetState(skipPartiallyExpanded=true)) {
        Column(Modifier.fillMaxWidth().testTag("shopping-composer").verticalScroll(rememberScrollState()).imePadding().padding(horizontal=20.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text("إضافة علاج للقائمة",style=MaterialTheme.typography.titleLarge)
                    Text("اسم أو صورة • والكمية إن احتجت",style=MaterialTheme.typography.bodySmall) }
                IconButton(enabled=!busy,onClick={store.photos.discardDraft(id);onDismiss()}) { Icon(Icons.Rounded.Close,"إغلاق الإضافة") }
            }
            OutlinedTextField(title,{if(it.length<=240) title=it},label={Text("اسم العلاج")},singleLine=true,
                modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp),keyboardOptions=KeyboardOptions(imeAction=ImeAction.Next))
            OutlinedTextField(note,{if(it.length<=1000) note=it},label={Text("الكمية أو ملاحظة (اختياري)")},maxLines=3,
                modifier=Modifier.fillMaxWidth(),shape=RoundedCornerShape(16.dp))
            if(photo) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                key(id,imageRevision) { ShoppingPhoto(store.photos.draft(id),true,Modifier.size(76.dp)) }
                Column(Modifier.weight(1f)) { Text("الصورة محفوظة على هذا الهاتف",style=MaterialTheme.typography.labelMedium)
                    Text("لن تُرفع للسحابة. الاسم والملاحظة يظهران للجميع.",style=MaterialTheme.typography.bodySmall) }
                IconButton(enabled=!busy,onClick={store.photos.discardDraft(id);photo=false}) { Icon(Icons.Rounded.DeleteOutline,"إزالة الصورة") }
            }
            Row(horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                OutlinedButton(enabled=!busy,onClick={runCatching { gallery.launch(arrayOf("image/*")) }.onFailure {error="تعذر فتح معرض الصور."}},modifier=Modifier.weight(1f)) {
                    Icon(Icons.Rounded.PhotoLibrary,null,Modifier.size(20.dp)); Spacer(Modifier.width(6.dp)); Text("من الصور") }
                OutlinedButton(enabled=!busy,onClick={
                    runCatching {
                        val directory=File(context.cacheDir,"shopping_camera").apply {mkdirs()}
                        val file=File(directory,"${id}.jpg");cameraPath=file.path
                        camera.launch(FileProvider.getUriForFile(context,"${context.packageName}.fileprovider",file))
                    }.onFailure {error="لا يوجد تطبيق كاميرا متاح؛ يمكنك الاختيار من الصور."}
                },modifier=Modifier.weight(1f)) { Icon(Icons.Rounded.PhotoCamera,null,Modifier.size(20.dp));Spacer(Modifier.width(6.dp));Text("الكاميرا") }
            }
            if(!photo) Text("الصور محلية فقط. إضافة الاسم تساعد زملاءك في تمييز العلاج.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
            if(error!=null) Text(error.orEmpty(),color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
            Button(onClick={save(true)},enabled=!busy&&(title.isNotBlank()||photo),modifier=Modifier.fillMaxWidth().heightIn(min=50.dp)) {
                Icon(Icons.Rounded.Add,null);Spacer(Modifier.width(8.dp));Text(if(busy) "جارٍ الحفظ…" else "حفظ وإضافة علاج آخر") }
            TextButton(onClick={save(false)},enabled=!busy&&(title.isNotBlank()||photo),modifier=Modifier.fillMaxWidth()) { Text("حفظ وإغلاق") }
        }
    }
}
