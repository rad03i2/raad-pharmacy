package com.radwan.raadpharmacy.demo

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.fragment.app.FragmentActivity
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.radwan.raadpharmacy.ui.theme.PharmacyLedgerTheme
import java.util.Locale

/** Entirely offline presentation: no Room/Supabase/real customers or editable production data. */
class DemoMarketingActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PharmacyLedgerTheme { DemoDisplay { finish() } } }
    }
}

internal data class DemoCustomer(val name: String, val area: String, val due: Long, val avatar: Int)
internal object DemoFixtures {
    const val username = "demo"
    const val password = "demo2026"
    val staff = listOf("رعد", "أحمد", "فؤاد")
    val customers = listOf(
        DemoCustomer("علي حسن", "حي الجامعة", 42000, 0),
        DemoCustomer("محمد جاسم", "حي الزهور", 18000, 1),
        DemoCustomer("سارة أحمد", "حي النور", 0, 2),
        DemoCustomer("نور مصطفى", "المنصور", 66000, 3),
        DemoCustomer("حسين عباس", "الجامعة", 29000, 4),
        DemoCustomer("زينب علي", "حي الصحة", 9000, 5),
        DemoCustomer("كرار محمود", "الأطباء", 0, 6),
        DemoCustomer("مريم خالد", "حي الربيع", 37000, 7),
        DemoCustomer("مصطفى عدنان", "الزهور", 14000, 8),
        DemoCustomer("هبة كريم", "حي النصر", 51000, 9)
    )
    val total = customers.sumOf { it.due }
    fun money(value: Long) = "%,d د.ع".format(Locale.US, value)
}

@Composable
private fun DemoDisplay(exit: () -> Unit) {
    var loggedIn by remember { mutableStateOf(false) }
    var user by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf("الرئيسية") }
    var selected by remember { mutableStateOf<DemoCustomer?>(null) }
    var search by remember { mutableStateOf("") }
    BackHandler {
        if (selected != null) selected = null
        else if (page != "الرئيسية") page = "الرئيسية"
        else exit()
    }

    if (!loggedIn) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.Center) {
                Text("دفتر صيدلية رعد", style = MaterialTheme.typography.headlineMedium)
                Text("حساب العرض الإعلامي • بيانات وهمية",
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(24.dp))
                OutlinedTextField(user, { user = it; error = false },
                    label = { Text("اسم المستخدم") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(password, { password = it; error = false },
                    label = { Text("كلمة المرور") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth())
                if (error) Text("اسم المستخدم أو كلمة المرور غير صحيح.",
                    color = MaterialTheme.colorScheme.error)
                Button(onClick = {
                    if (user.trim().lowercase(Locale.US) == DemoFixtures.username &&
                        password == DemoFixtures.password) loggedIn = true
                    else error = true
                }, modifier = Modifier.fillMaxWidth()) { Text("الدخول إلى العرض") }
                TextButton(onClick = exit) { Text("الرجوع إلى التطبيق") }
            }
        }
        return
    }

    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("دفتر صيدلية رعد", style = MaterialTheme.typography.titleLarge,
                            modifier = Modifier.weight(1f))
                        TextButton(onClick = {
                            if (selected != null) selected = null else exit()
                        }) { Text("رجوع") }
                    }
                    Text("وضع العرض • كل المعلومات والأرصدة والصور افتراضية",
                        style = MaterialTheme.typography.labelSmall)
                }
            }
        },
        bottomBar = {
            if (selected == null) NavigationBar {
                listOf("الرئيسية", "الزبائن", "الحركات").forEach { tab ->
                    NavigationBarItem(selected = tab == page,
                        onClick = { page = tab; search = "" },
                        icon = { Text("●") }, label = { Text(tab) })
                }
            }
        }
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (selected != null) {
                val customer = selected!!
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DemoAvatar(customer.avatar, 70)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(customer.name, style = MaterialTheme.typography.headlineSmall)
                            Text(customer.area)
                        }
                    }
                }
                item { DemoTile("الرصيد الحالي", DemoFixtures.money(customer.due)) }
                item { DemoTile("دين تجريبي", DemoFixtures.money(customer.due + 10000)) }
                item { DemoTile("تحصيل تجريبي", DemoFixtures.money(10000)) }
                item { Text("هذا السجل وهمي وغير متصل بالزبائن الحقيقيين.") }
            } else {
                if (page == "الرئيسية") {
                    item { DemoTile("إجمالي المبالغ المستحقة", DemoFixtures.money(DemoFixtures.total)) }
                    item { Text("فريق الصيدلية", style = MaterialTheme.typography.titleMedium) }
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            DemoFixtures.staff.forEachIndexed { i, name ->
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    DemoAvatar(i + 10, 58)
                                    Text(name)
                                }
                            }
                        }
                    }
                    item { Text("آخر الحسابات", style = MaterialTheme.typography.titleMedium) }
                }
                if (page == "الزبائن") item {
                    OutlinedTextField(search, { search = it }, singleLine = true,
                        label = { Text("البحث بالاسم") },
                        modifier = Modifier.fillMaxWidth())
                }
                if (page == "الحركات") {
                    item { Text("حركات مالية افتراضية", style = MaterialTheme.typography.titleMedium) }
                    items(DemoFixtures.customers.take(6)) { customer ->
                        DemoTile(customer.name + " • دين", DemoFixtures.money(customer.due + 10000))
                    }
                } else {
                    val visible = DemoFixtures.customers.filter {
                        (page != "الرئيسية" || it.avatar < 5) &&
                            (search.isEmpty() || it.name.contains(search, true))
                    }
                    items(visible, key = { it.avatar }) { customer ->
                        Surface(Modifier.fillMaxWidth().clickable { selected = customer },
                            shape = MaterialTheme.shapes.large, tonalElevation = 2.dp) {
                            Row(Modifier.fillMaxWidth().padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                DemoAvatar(customer.avatar, 52)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(customer.name, style = MaterialTheme.typography.titleMedium)
                                    Text(customer.area, style = MaterialTheme.typography.bodySmall)
                                }
                                Text(DemoFixtures.money(customer.due))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun DemoTile(label: String, value: String) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer) {
        Column(Modifier.padding(16.dp)) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.titleLarge)
        }
    }
}

/** Unique illustrated portraits for ten fictional customers and three display staff. */
@Composable private fun DemoAvatar(seed: Int, size: Int) {
    val tones = listOf(Color(0xFF79B4CE), Color(0xFFE9B69C), Color(0xFFB7B1DF),
        Color(0xFF8DCFB8), Color(0xFFDFA2B0))
    val background = tones[seed % tones.size]
    Canvas(Modifier.size(size.dp)) {
        val r = this.size.minDimension / 2
        drawCircle(Brush.linearGradient(listOf(background, Color(0xFF496477))), r)
        val skin = listOf(Color(0xFFE8B98D),Color(0xFFAF755B),Color(0xFFD49C79))[seed%3]
        val center = Offset(this.size.width/2, this.size.height*0.43f)
        drawCircle(Color(0xFF342C33), r*0.41f, center.copy(y=center.y-r*0.12f))
        drawCircle(skin, r*0.30f, center)
        drawOval(Color(0xFF354C61),
            topLeft=Offset(r*0.27f,r*1.23f),size=Size(r*1.46f,r*0.72f))
    }
}
