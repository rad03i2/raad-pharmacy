# دفتر صيدلية رعد — Raad Pharmacy Ledger

تطبيق Android عربي RTL لإدارة حسابات وديون زبائن صيدلية رعد، مصمم للاستخدام اليومي السريع خلف كاونتر الصيدلية.

## الهوية
- الاسم: دفتر صيدلية رعد
- الاسم المختصر: صيدلية رعد
- الهوية البصرية: Medical Blue + Turquoise + White
- الخط الافتراضي: Cairo
- Material 3 + Jetpack Compose مع RTL وأرقام إنجليزية ودينار عراقي.

## الوظائف الرئيسية
- إضافة وتعديل الزبائن وصورهم.
- تسجيل دين كمبلغ مالي مباشر مع حقل اختياري للمشتريات / الأدوية.
- تسجيل التحصيل ومنع تحصيل مبلغ أكبر من الرصيد الحالي.
- حماية من تسجيل الدين المكرر وتنبيه للمبالغ غير الطبيعية.
- إدخال مبلغ الدين والأوامر المالية بالصوت مع مراجعة المستخدم قبل الحفظ.
- كشف حساب احترافي قابل للمشاركة كصورة عبر WhatsApp.
- سجل الحركات وديون اليوم وتحصيلات اليوم وأعلى المديونيات والمناطق.
- تقارير وإحصائيات ومركز متابعة وتنبيهات.
- نسخ احتياطي واستعادة مع توافق قراءة النسخ القديمة.
- قفل PIN وبصمة ووضع داكن وخطوط عربية قابلة للتغيير.

## التقنية
- Kotlin
- Jetpack Compose
- Material 3
- Navigation Compose
- ViewModel + StateFlow
- Room 3 + SQLite (Offline-first)
- Supabase Auth + PostgreSQL + RLS + Realtime
- WorkManager للمزامنة المؤجلة وإعادة المحاولة
- Firebase Cloud Messaging + Crashlytics
- KSP
- Android API 26+
- Compile / Target SDK 37

## قاعدة البيانات والتوافق
قاعدة Room بقيت على Schema v1 في هذا التحويل لتجنب Migration غير ضرورية. حقلا bottles و bottle_price القديمـان موجودان فقط كتوافق Legacy لاستعادة البيانات القديمة، ولا يظهران في تجربة الصيدلية الجديدة ولا تستخدمهما عمليات الدين الجديدة.

## المزامنة السحابية
يدعم الإصدار v3.3.1 إشعارات سحابية لحظية مع صندوق أحداث دائم، وتجميع الإشعارات التي فاتت أثناء انقطاع الإنترنت، وصور زبائن وصور مستخدمين متزامنة، وحالة اتصال وآخر ظهور للحسابات.

\nيعتمد الإصدار 3.2.0 على أحداث PostgreSQL Realtime المباشرة لتحديث الهواتف لحظيًا، مع تنبيه علوي داخل التطبيق عند عمليات الأجهزة الأخرى، وإعادة اتصال تلقائية عند العودة للتطبيق، وPull-to-refresh في الرئيسية كتحديث يدوي إضافي.\n\n
يبقى Room هو مصدر العمل المحلي السريع، وتُسجّل التغييرات في Journal محلي ثم تُرسل إلى Supabase فور توفر الشبكة. يدعم النظام إعادة المحاولة عبر WorkManager، استقبال تغييرات الأجهزة الأخرى عبر Realtime، تسجيل كل جهاز وFCM Token، وتسجيل الدخول باسم مستخدم وكلمة مرور دون إظهار البريد التقني الداخلي.

## الحزمة
- Namespace: com.radwan.raadpharmacy
- Application ID: com.radwan.raadpharmacy

## البناء
يتحقق GitHub Actions من اختبارات الوحدة وRoom وCompose ثم lintRelease ويبني Release APK. الإصدار الحالي مع المزامنة اللحظية هو v3.3.1 (versionCode 32).nCode 28).

## ملاحظات تاريخية
ملاحظات إصدارات التطبيق السابق نُقلت إلى docs/legacy للحفاظ على التاريخ التقني دون تقديمها كتوصيف للمنتج الحالي.

## Update 3.3.2
- Bundled Pixabay notification sound in Android channels; independent audible alerts in foreground and background.
- Sequential missed-event delivery with cross-path deduplication and paginated catch-up.
- FCM data callbacks persist delivery work instead of fetching missed events synchronously.
- Search opens the keyboard immediately and uses the name/phone hint.
- Home wallet opens all customer movements with day, last-7-days, month and all filters.
- English digits with Arabic 12-hour morning/evening labels.

Backend push still requires a valid Firebase service account JSON configured as `FIREBASE_SERVICE_ACCOUNT_JSON`. Client `google-services.json` is not that credential. Server SQL grants are recorded in `supabase/push-access.sql`; only the backend can update dispatch metadata.

## تحديث 3.3.3
- استعادة العمل المجدول بعد تشغيل الهاتف (بعد فك القفل) وتحديث التطبيق، ومزامنة معلقة عند مغادرة الواجهة. WorkManager وFCM يعملان دون بقاء الواجهة مفتوحة، ويخضعان لقيود أندرويد والبطارية. الإيقاف الإجباري يمنع الاستئناف حتى فتح التطبيق. لا تستخدم خدمة دائمة غير محدودة أو صلاحيات تجاوز البطارية.
- الإشعارات الفورية تتطلب إعداد `FIREBASE_SERVICE_ACCOUNT_JSON` في أسرار Supabase؛ استمرار العمل المجدول لا يعوض هذا المفتاح.
- منع HTTP، الثقة بشهادات النظام فقط، نسخ تلقائي مشفر ومقتصر على قاعدة الدفتر دون الجلسات أو PIN أو معرف الجهاز، وإخفاء بيانات إشعارات السحابة عند تفعيل إخفاء المبالغ.
- خمس محاولات PIN خاطئة تفرض مهلة تبدأ من 30 ثانية وتصل إلى خمس دقائق، وتستمر عند إعادة فتح التطبيق. التحقق واشتقاق PIN وحساب فهارس الدفتر خارج مسار الواجهة.
- جمع طلبات المزامنة المتزامنة، جلب التغييرات بدل استبدال قاعدة البيانات كل مرة، وجلب النتائج على صفحات. تحديث الزبون يحافظ على الحركات المرتبطة به.
- اختصار سجل الحركات بدل المناطق، وحركة قصيرة عند اختيار كل أيقونة تنقل سفلية.

### التوقيع والفحص
يمكن توفير مفتاح توقيع خاص وثابت في GitHub Actions عبر الأسرار `RAAD_SIGNING_KEYSTORE_BASE64`, `RAAD_KEYSTORE_PASSWORD`, `RAAD_KEY_ALIAS`, `RAAD_KEY_PASSWORD`. للبناء المحلي استخدم `RAAD_KEYSTORE_PATH` مع قيم التوقيع الثلاث. لا تُرفع ملفات المفاتيح أو كلمات مرورها إلى المستودع. غيابها ينتج APK تجريبيًا بمفتاح debug؛ لا يصلح للنشر في Play ولا يُضمن توافق تحديثه مع النسخة المثبتة. الحفاظ على بيانات التحديث يتطلب مفتاح النسخة الأصلية نفسه.

CI يشغل الاختبارات وlint ويتحقق من توقيع APK. هذه فحوص بناء فعلية وليست اعتماد Play Protect أو ضمان أمان 100%؛ مراجعة Play وفحوص الجهاز لا يمكن تجاوزها.
