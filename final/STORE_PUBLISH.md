# دليل النشر التفصيلي — نقل آمن / Secure Share

هذا الدليل لشخص لم ينشر تطبيقا من قبل. اتبع الخطوات بالترتيب. لا تتخط أي خطوة فيها حساب أو توقيع.

الناتج المطلوب لمتجر بلاي هو ملف AAB وليس APK.

البناء الحالي عبر:

```bash
cd p2p-android
chmod +x build-android.sh
./build-android.sh
```

ينتج:

- `dist/p2p-secure-share.apk` للاختبار على الهاتف
- `dist/p2p-secure-share.aab` للرفع إلى Google Play

بدون مفتاح إصدار رسمي يُوقَّع AAB بتوقيع تصحيح. بلاي يرفض هذا التوقيع. يجب إنشاء مفتاح إصدار قبل الرفع النهائي.

---

# الجزء أ — Google Play من الصفر

## أ1. ماذا تحتاج قبل أي رفع

- جواز سفر أو بطاقة هوية سارية للتحقق من الهوية
- بطاقة دفع دولية (Visa أو Mastercard) لدفع رسوم التسجيل لمرة واحدة 25 دولار أمريكي
- بريد Gmail لا يُستخدم لحساب مطور آخر
- رقم هاتف يستقبل رسائل
- موقع أو صفحة HTTPS لسياسة الخصوصية (إجباري)
- جهاز أندرويد للاختبار أو محاكي
- جهاز كمبيوتر لبناء AAB

التطبيق الحالي جاهز من ناحية السياسة العامة:

- لا يطلب All files access
- اختيار الملف عبر SAF
- targetSdk 35
- لا إعلانات ولا تتبع ولا حسابات
- سياسة خصوصية داخل `app/src/main/assets/privacy_policy.html`

## أ2. إنشاء حساب Google Play Console

1. ادخل من المتصفح: https://play.google.com/console
2. سجّل الدخول بحساب Google
3. اضغط Associate a developer account ثم Create account
4. املأ:
   - الاسم الظاهر للمطور (سيظهر تحت اسم التطبيق)
   - البريد للتواصل
   - الدولة
5. ادفع 25 دولار مرة واحدة
6. أكمل التحقق من الهوية (Identity verification). قد يطلب صورة الهوية وصورة شخصية. المراجعة قد تستغرق ساعات أو عدة أيام
7. لا تحاول رفع تطبيق قبل اكتمال التحقق

بعد القبول ستصل إلى لوحة Play Console.

## أ3. إنشاء مفتاح التوقيع (مهم جدا — احفظه مدى الحياة)

Google Play يطلب أن يكون كل تحديث موقّعا بنفس المفتاح أو عبر Play App Signing. أنشئ مفتاحك مرة واحدة ولا تفقده.

على لينكس أو ويندوز (Git Bash / WSL):

```bash
keytool -genkeypair -v \
  -keystore "$HOME/p2p-upload.jks" \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -alias upload \
  -storepass 'ضع-كلمة-سر-قوية' \
  -keypass 'ضع-كلمة-سر-قوية' \
  -dname "CN=Secure Share,OU=Mobile,O=Your Name,L=Cairo,ST=Cairo,C=EG"
```

احفظ في مكان آمن خارج المشروع:

- ملف `p2p-upload.jks`
- كلمة سر المتجر
- الاسم المستعار `upload`
- كلمة سر المفتاح

إذا ضاع هذا الملف لن تستطيع تحديث التطبيق بنفس الهوية إلا عبر Play App Signing إن كان مفعّلا.

لا ترفع ملف `.jks` إلى Git ولا تضعه داخل zip التطبيق.

## أ4. بناء AAB موقّع للإصدار

```bash
cd p2p-android
export KEYSTORE_FILE="$HOME/p2p-upload.jks"
export KEYSTORE_PASSWORD='كلمة-سر-المتجر'
export KEY_ALIAS=upload
export KEY_PASSWORD='كلمة-سر-المفتاح'
./build-android.sh
```

تأكد من وجود:

```text
dist/p2p-secure-share.aab
```

هذا هو الملف الذي يُرفع إلى بلاي. APK للاختبار المحلي فقط:

```bash
adb install -r dist/p2p-secure-share.apk
```

## أ5. نشر سياسة الخصوصية على رابط عام

بلاي يطلب رابط HTTPS عام. انسخ محتوى:

`p2p-android/app/src/main/assets/privacy_policy.html`

انشره في أحد هذه الأماكن:

- GitHub Pages
- موقعك الشخصي
- Google Sites (إن ظهر HTTPS)

عدّل فقرة Contact وضع بريد الدعم الحقيقي قبل النشر.

مثال رابط صالح:

```text
https://yourname.github.io/secure-share/privacy.html
```

## أ6. إنشاء التطبيق في Play Console

1. Home ثم Create app
2. App name: `نقل آمن` أو `Secure Share`
3. Default language: Arabic أو English
4. App or game: App
5. Free or paid: Free
6. وافق على السياسات ثم Create

## أ7. تعبئة بيانات المتجر (Store listing)

من القائمة: Grow then Store presence then Main store listing

املأ بالعربية والإنجليزية إن أمكن.

الاسم:

```text
نقل آمن
```

العنوان القصير (حتى 80 حرفا تقريبا حسب الواجهة):

```text
إرسال ملف مشفر عبر رابط متصفح
```

الوصف القصير:

```text
أرسل ملفا واحدا مشفرا. المستلم يفتح رابطا في المتصفح بدون تثبيت.
```

الوصف الكامل:

```text
نقل آمن يرسل ملفا واحدا من هاتفك إلى أي شخص عبر رابط.
المستلم لا يثبت تطبيقا، بل يفتح الصفحة في المتصفح ثم يضغط قبول أو رفض مرة واحدة للملف كله.

التشفير من طرف إلى طرف بمفاتيح ECDH وAES-256-GCM. الملف لا يُقرأ على خادم المطور.
القراءة من القرص عند المرسل والكتابة على القرص أو التخزين الخاص بالمتصفح عند المستلم حتى تعمل الملفات الكبيرة.

كيف يعمل:
1. تفتح التطبيق وتبقيه مفتوحا
2. يظهر رابط إنترنت للمستلم
3. المستلم يفتح الرابط وينسخ الرقم التعريفي
4. تلصق الرقم وتختار ملفا واحدا
5. المستلم يوافق مرة واحدة فيبدأ النقل المشفر ويستمر حتى النهاية حتى لو تبدل النفق

الصلاحيات:
- الإنترنت لتشغيل رابط الاستلام ونقل الملف الذي تختاره
- الإشعارات لإظهار أن النقل جار
- اختيار ملف واحد من منتقي النظام
- الصور والفيديو اختيارية وليست مطلوبة للإرسال العادي

لا حسابات. لا إعلانات. لا تتبع. لا جمع جهات اتصال.
أبق التطبيق مفتوحا حتى يكتمل الإرسال.
```

الصور المطلوبة تقريبا:

- أيقونة عالية الدقة 512x512 PNG
- صورة مميزة Feature graphic 1024x500
- هاتف: على الأقل لقطتان، ويفضّل 4 إلى 8 لقطات من التطبيق وهو يعمل
- جهاز لوحي اختياري

لا تضع كلمات مثل أفضل أو رقم 1 في الصور.

فئة التطبيق المقترحة:

- Tools أو Productivity

علامات:

```text
file transfer, encryption, p2p, share
```

بريد التواصل: بريدك
الموقع: رابط سياسة الخصوصية أو صفحة بسيطة
رقم الهاتف: اختياري لكن مفيد للمراجعة

## أ8. سياسة الخصوصية داخل Console

Store settings أو App content ثم Privacy policy

ضع رابط HTTPS الذي نشرته في أ5.

## أ9. Data safety

App content ثم Data safety

املأ كالتالي بما يطابق التطبيق الحالي:

- Does your app collect or share user data: يمكنك اختيار أن الملف يُعالج مؤقتا ولا يُجمع على خادم المطور
- Data collected by developer: لا حساب، لا موقع، لا جهات اتصال، لا إعلانات
- Files and docs: processed temporarily during transfer, not stored by the developer
- Data shared with third parties: No (الأنفاق مثل Serveo/Pinggy تمرر بيانات مشفرة لكنك لا تبيع بيانات)
- Encryption in transit: Yes
- Users can request deletion: Not applicable because you do not store personal data on your servers
- Security practices: Data is encrypted in transit

كن صادقا. لا تقل إنك لا تستخدم الإنترنت.

## أ10. App access / محتوى التطبيق

App content:

- Ads: No
- Target audience: 18+ أو 13+ حسب اختيارك. هذا التطبيق أداة نقل ملفات، الأنسب 13+ أو 18+
- News app: No
- COVID: No
- Data safety: كما في أ9
- Government apps: No
- Financial features: No
- Health: No

Permissions declaration:

- INTERNET: لنفق الاستلام ونقل الملف
- POST_NOTIFICATIONS: لإشعار النقل
- FOREGROUND_SERVICE / DATA_SYNC: لإبقاء النقل حيا أثناء الإرسال
- WAKE_LOCK: لعدم نوم الجهاز أثناء النفق
- READ_MEDIA_IMAGES / VIDEO: اختيارية عبر المنتقي وليست All files

لا تعلن Storage All files. التطبيق يستخدم SAF.

## أ11. Play App Signing

عند أول رفع AAB ستظهر Play app signing. اقبل الخيار الافتراضي:

Google يدير مفتاح التطبيق، وأنت ترفع بـ upload key (ملف jks الذي أنشأته).

هذا أأمن إذا فقدت المفتاح لاحقا، بشرط ألا تفقد upload key قبل ربطه.

## أ12. إنشاء الإصدار: Internal testing أولا

لا ترفع مباشرة إلى Production في أول مرة.

1. Test and release ثم Testing ثم Internal testing
2. Create new release
3. ارفع `dist/p2p-secure-share.aab`
4. Release name: `1.0.0`
5. Release notes بالعربية والإنجليزية:

```text
الإصدار الأول: إرسال ملف مشفر عبر رابط متصفح، مع أنفاق متعددة ومراقبة الاتصال.
```

```text
First release: encrypted one-file transfer via a browser link, multi-path tunnels, connection monitor.
```

6. احفظ ثم راجع ثم ابدأ التسليم إلى المختبرين الداخليين
7. أضف بريد Gmail واحد على الأقل كمختبر
8. ثبّت من رابط الاختبار الداخلي على هاتفك وتأكد أن:
   - شاشة الموافقة تظهر
   - الرابط يظهر
   - الإرسال يعمل
   - الإشعار يبقى

## أ13. فيديو المراجعة (مهمة جدا لأول تطبيق)

المراجع البشري في بلاي قد يطلب فيديو إذا رأى صلاحيات شبكة وخدمة أمامية.

سجّل 30 إلى 60 ثانية بدون مونتاج معقّد:

1. افتح التطبيق
2. اضغط موافق وبدء
3. انتظر ظهور الرابط الحقيقي
4. انسخ الرابط
5. على جهاز آخر أو متصفح افتح الرابط
6. انسخ الرقم التعريفي
7. الصقه في التطبيق
8. اختر ملفا واحدا من المنتقي
9. اضغط قبول في المتصفح
10. شاهد اكتمال النقل

ارفع الفيديو على YouTube غير مدرج Unlisted وضع رابطه في ملاحظات المراجعة.

## أ14. Countries / المحتوى / التصنيف

- Countries: اختر كل الدول أو دولك فقط
- Content rating: املأ الاستبيان. هذا التطبيق أدوات، بلا عنف وبلا محتوى جنسي. النتيجة المتوقعة Everyone أو PEGI 3 حسب الإجابات
- لا تختر فئة أطفال إلا إذا التزمت بسياسات Designed for Families وهذا التطبيق غير مناسب لتلك البرنامج

## أ15. الانتقال إلى Production

بعد نجاح الاختبار الداخلي:

1. Production ثم Create new release
2. يمكنك Promote من Internal testing إلى Production
3. أكمل كل العلامات الخضراء في Dashboard (سياسة، تصنيف، listing، data safety)
4. Send for review

المراجعة الأولى قد تأخذ أيام. أول حساب جديد أبطأ.

ارفض أي رسالة تطلب تغيير اسم الحزمة إن لم تطلبها أنت. applicationId الحالي:

```text
com.p2psecure.share
```

لا تغيّره بعد أول نشر.

## أ16. أسباب رفض شائعة وكيف تتجنبها

- رفع APK بدل AAB: ارفع AAB
- توقيع debug: وقّع بمفتاح الإصدار
- سياسة خصوصية ناقصة أو HTTP: استخدم HTTPS وعدّل بريد التواصل
- All files access: هذا التطبيق لا يستخدمها. لا تفعّلها
- الخدمة الأمامية بدون إشعار واضح: الإشعار موجود. أظهره في فيديو المراجعة
- وصف يعد بخصوصية مطلقة بينما تستخدم أنفاقا عامة: اكتب أن النقل مشفر وأن النفق وسيط TCP لا يقرأ الملف
- اختبار مكسور: لا ترفع نسخة لا تفتح
- أيقونة أو لقطات منخفضة الجودة
- ذكر ngrok كمتطلب إجباري بينما هو اختياري

## أ17. بعد القبول

- حدّث versionCode في `app/build.gradle.kts` مع كل رفع جديد (1 ثم 2 ثم 3)
- أبق versionName واضحا مثل 1.0.1
- ابنِ AAB بنفس المفتاح
- ارفع إصدارا جديدا من Production

---

# الجزء ب — AppGallery لهواتف هواوي

هواتف هواوي الجديدة في بعض المناطق بلا خدمات جوجل. النشر على AppGallery يوصل لنفس المستخدمين.

ليس إلزاميا، لكنه يعمل بنفس AAB أو APK.

## ب1. الحساب

1. https://developer.huawei.com
2. سجّل حساب مطور أفراد أو شركة
3. تحقق الهوية (جواز/بطاقة). قد يستغرق أياما
4. الأفراد غالبا بلا رسوم تسجيل مثل بلاي، لكن تحقق الصفحة الحالية لأن السياسة تتغير

## ب2. إنشاء التطبيق

1. AppGallery Connect ثم My apps ثم New
2. Package name يجب أن يطابق:

```text
com.p2psecure.share
```

3. الفئة: Tools
4. اللغة: العربية والإنجليزية

## ب3. الملف المرفوع

AppGallery يقبل:

- APK موقّع بإصدار
- أو AAB في بعض المسارات الحديثة

الأضمن لأول مرة: ارفع APK الموقّع بمفتاح الإصدار نفسه.

إذا كان عندك AAB فقط، جرب رفعه. إن رفض، ابنِ APK:

الناتج موجود أصلا بعد `./build-android.sh` في:

```text
dist/p2p-secure-share.apk
```

بشرط أن تكون متغيرات المفتاح مفعّلة كما في أ4. بدونها التوقيع debug وقد يُرفض في المتجر.

## ب4. بيانات المتجر على هواوي

يمكنك نسخ نفس النصوص من بلاي:

- الاسم: نقل آمن
- الوصف القصير والكامل
- سياسة الخصوصية HTTPS
- لقطات الشاشة
- تصنيف عمري
- بريد الدعم

Huawei تطلب أحيانا:

- شرح الصلاحيات واحدة واحدة
- اختبار حساب إن كان هناك تسجيل دخول (هذا التطبيق لا يحتاج)
- هل يجمع بيانات في الصين أم لا. أجب لا إذا كنت لا تجمع

## ب5. التوقيع على هواوي

يمكنك استخدام نفس upload key. لا تنشئ مفتاحا ثانيا إلا إذا اضطرك النظام.

فعّل App Signing في AppGallery إن ظهر الخيار، أو ارفع APK موقّعا مباشرة.

## ب6. المراجعة

قد يطلبون فيديو مشابه لبلاي. استخدم نفس فيديو YouTube غير المدرج.

بعد القبول يظهر التطبيق في AppGallery، وعلى هواتف هواوي ذات خدمات جوجل سيظهر أيضا من بلاي إن نشرته هناك.

---

# الجزء ج — قائمة تحقق قبل الضغط على Send for review

- حساب المطور موثّق
- مفتاح `p2p-upload.jks` محفوظ خارج الجهاز العام
- `dist/p2p-secure-share.aab` مبني بالمفتاح
- رابط خصوصية HTTPS يعمل
- لقطات وأيقونة 512 جاهزة
- Data safety مكتمل
- Internal testing ثُبّت على هاتف حقيقي
- فيديو المراجعة جاهز
- لا توجد كلمات مضللة في الوصف
- versionCode = 1 لأول رفع

---

# بيانات جاهزة للنسخ

Application id:

```text
com.p2psecure.share
```

الاسم:

```text
نقل آمن
Secure Share
```

الفئة:

```text
Tools
```

سياسة الخصوصية المحلية قبل الرفع:

```text
p2p-android/app/src/main/assets/privacy_policy.html
```

أمر البناء الرسمي:

```bash
cd p2p-android
export KEYSTORE_FILE="$HOME/p2p-upload.jks"
export KEYSTORE_PASSWORD='YOUR_STORE_PASS'
export KEY_ALIAS=upload
export KEY_PASSWORD='YOUR_KEY_PASS'
./build-android.sh
```

ملفات الرفع:

```text
dist/p2p-secure-share.aab
dist/p2p-secure-share.apk
```
