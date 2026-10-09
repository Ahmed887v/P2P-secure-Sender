final.zip

1. STORE_PUBLISH.md
   خطوات النشر من الصفر على Google Play وHuawei AppGallery، وبناء AAB الموقّع.

2. SOURCE_CODE.txt
   كل ملفات المصدر التي تشغّل التطبيق، مفصولة بعنوان واضح وسطر فارغ. يشمل FileSource.kt ومسار البث.

3. ARCHITECTURE.md
   خريطة هيكل العمل من الفتح حتى اكتمال النقل، مع القراءة والكتابة المتدفقة والاستئناف المحفوظ.

البناء من مشروع p2p-android:

chmod +x build-android.sh
./build-android.sh

APK للاختبار: dist/p2p-secure-share.apk
AAB للمتجر: dist/p2p-secure-share.aab

الإرسال: قطعة 49152 من القرص. القبول مرة واحدة. الاستئناف من آخر قطعة سليمة.
