# Quick Voice — تطبيق مكالمات Android (SIM + Wi-Fi / VoIP)

تطبيق أندرويد احترافي لإجراء المكالمات بطريقتين:

1. **مكالمات SIM** عبر إطار Android Telecom الرسمي.
2. **مكالمات VoIP** عبر Wi-Fi / الإنترنت باستخدام **WebRTC**.

مع ميزة **Quick Voice / Talk Without Opening Call**: تسجيل رسالة صوتية قصيرة بزر ضغط-و-تكلم (Hold-to-Talk) من شاشة المكالمة نفسها، بدون التنقل بين شاشات.

> ملاحظة الأمانة الفنية: كل ما ليس مسموحًا به على Android معرّف في هذا المستند بوضوح مع البديل القانوني المستخدم، لا يوجد أي "تحايل" على النظام.

---

## 1. تحليل القدرات على Android الحديث (خاص بمكالمات SIM)

هذا هو الجزء الأهم: ما الذي يسمح به Android فعليًا للتطبيقات العادية عند التعامل مع مكالمات SIM؟

### ما يمكن للتطبيق فعله عبر Telecom APIs الرسمية

| الإمكانية | الحالة | التفاصيل |
|---|---|---|
| إجراء مكالمة SIM | ✅ مسموح | `TelecomManager.placeCall(uri, extras)` يحتاج صلاحية `CALL_PHONE`، أو أن يكون التطبيق هو **Dialer الافتراضي**. |
| معرفة حالة المكالمة (رنين، نشطة، منتهية…) | ✅ مسموح | عبر `InCallService.onCallAdded` و `Call.Callback.onStateChanged` — **بشرط أن يكون التطبيق هو default dialer**. |
| الرد / الرفض / إنهاء المكالمة | ✅ مسموح | `Call.answer()`, `Call.reject()`, `Call.disconnect()` — عبر `InCallService` (default dialer). |
| كتم الميكروفون (mute) | ✅ مسموح | `AudioManager.setMicrophoneMute()` أثناء مكالمة نشطة. |
| تشغيل الـ Speaker تلقائيًا | ⚠️ بشروط | الطريقة الموثوقة على Android 12+ هي `AudioManager.setCommunicationDevice(TYPE_BUILTIN_SPEAKER)`. على Android 14+ هذه الدالة تتطلب صلاحية **signature-level** `MODIFY_AUDIO_ROUTING`، لذا قد ترمي `SecurityException` في بعض الأجهزة. في هذه الحالة يستخدم التطبيق البديل القانوني المتاح (إما عبر Telecom `Call.setAudioRoute` — متاح فقط للـ default dialer — أو `setSpeakerphoneOn` القديمة، أو يعرض التنبيه بوضوح). |
| **استخدام الميكروفون وإرسال الصوت في مكالمة SIM بدون فتح واجهة المكالمة** | ❌ **غير مسموح** | لا توجد أي API عامة في Android لإدخال صوت داخل بث مكالمة هاتفية (inject audio إلى uplink). هذا **ممنوع من النظام عمدًا** لحماية الخصوصية، وليس نقصًا في الكود. |
| التحكم بمسار الصوت (سماعة أذن / speaker / بلوتوث) | ⚠️ بشروط | عبر `Call.setAudioRoute()` فقط للـ default dialer؛ أو `AudioManager.setCommunicationDevice()` (قيود الأذونات أعلاه). |
| معرفة أن الطرف الآخر لم يرد (No Answer) | ⚠️ بشروط | نعم عبر حالات `Call` (`STATE_RINGING` ثم `STATE_DISCONNECTED` بدون `STATE_ACTIVE`) — بشرط default dialer. يمكننا وقتها تفعيل وضع Quick Voice وفتح الـ Speaker. |

### قيود أساسية يجب معرفتها

- **الصلاحيات**: `CALL_PHONE` تُمنح للتطبيقات العادية، لكن **`InCallService` لا يستقبل أي كولباك إلا إذا كان التطبيق هو default dialer** (دور `ROLE_DIALER`). لذلك:
  - بدون default dialer → يستطيع التطبيق **إجراء** المكالمة فقط، ويُعاد المستخدم إلى تطبيق الهاتف النظامي، ولا توجد أي معلومات عن حالة المكالمة.
  - مع default dialer → المكالمة تبقى داخل التطبيق مع كل التحكم.
- **`AudioManager.setSpeakerphoneOn()`** لم تعد تعمل بشكل موثوق منذ Android 10 (Q) واستُبدلت بـ `setCommunicationDevice`. وعلى Android 14+ أضيفت صلاحية `MODIFY_AUDIO_ROUTING` (signature) لتقييد توجيه الصوت.
- **لا يمكن لأي تطبيق** (حتى default dialer) تسجيل الطرف الآخر أو إدخال صوت إلى مكالمة SIM بدون إذن نظام خاص؛ وهذه قيود قصدية.

### البديل القانوني المُنفَّذ لميزة Quick Voice على مكالمات SIM

بما أن إدخال الصوت في مكالمة SIM مستحيل، نفّذنا **أقرب بديل عملي وأمين**:

1. عند تفعيل Quick Voice في مكالمة SIM غير مجاب عنها → **يُشغَّل الـ Speaker تلقائيًا** (حيث تسمح الصلاحيات).
2. عند الضغط والتحدث → يُسجَّل المقطع (2–5 ثوانٍ).
3. ثم:
   - إذا كان لدى الطرف الآخر **VoIP ID** مرتبط بجهة الاتصال (لأنه يستخدم التطبيق أيضًا) → يُرسَل المقطع **رسالة صوتية** عبر خادم الإشارة، ويستقبلها كـ Quick Voice.
   - إذا لم يكن → **يُشغَّل المقطع بصوت عالٍ على الـ Speaker** مع رسالة واضحة: "مكالمات SIM لا يمكنها إدخال صوت — تم تشغيله بصوت عالٍ".

هذا هو السلوك الصادق الذي طلبتَه: لا ندّعي أن الصوت وصل عبر شبكة الهاتف وهو لم يصل.

---

## 2. بنية المشروع (Modules)

```
QuickVoiceDialer/
├── app/                    # تطبيق رئيسي: Activity, DI graph (Hilt), Manifest
├── core/
│   ├── model/              # نماذج مشتركة: CallSession, CallState, Contact, ...
│   ├── call/               # CallController: مصدر الحقيقة الوحيد للمكالمة النشطة
│   ├── audio/              # CallAudioManager: توجيه الصوت لتطبيق VoIP
│   ├── data/               # DataStore (إعدادات) + Room (سجل المكالمات، روابط VoIP)
│   ├── design/             # مكونات Compose و الثيم
│   ├── telecom/            # SIM: SimCallManager, CallInCallService, DefaultDialerManager
│   ├── voip/               # WebRTC: VoipCallManager, VoipCallService, SignalingClient
│   └── quickvoice/         # محرك Quick Voice: تسجيل، تشغيل، تحكم
├── feature/
│   ├── home/               # الشاشة الرئيسية (Contacts / Dial Pad / Recent)
│   ├── call/               # شاشة المكالمة النشطة
│   ├── quickvoice/         # زر Hold-to-Talk + مؤشر التسجيل
│   └── settings/           # الإعدادات والصلاحيات
└── server/                 # خادم إشارة WebSocket (Node.js) لمكالمات VoIP
```

### مسؤولية كل وحدة

| الوحدة | الوظيفة |
|---|---|
| `core:call` | `CallController` يوحّد حالة المكالمة من النقلين (SIM/VoIP) في `StateFlow<CallSession?>`. كل نقل يُنشر "نقاط اتصال" من نوع `CallCommandSink` (إنهاء/رد/كتم/Speaker). الواجهة تتعامل معه فقط. |
| `core:telecom` | `SimCallManager` (إجراء مكالمة)، `CallInCallService` (استقبال المكالمة من Telecom وترجمتها لـ `CallSession`)، `DefaultDialerManager` (طلب دور default dialer). |
| `core:voip` | `VoipCallManager` (محرك WebRTC: offer/answer/ICE، DataChannel لـ Quick Voice، إعادة اتصال ICE عند تغيّر الشبكة)، `VoipCallService` (خدمة أمامية تُبقي المكالمة حيّة + إشعارات)، `SignalingClient` (WebSocket مع إعادة اتصال تلقائية). |
| `core:quickvoice` | `QuickVoiceController` (الأتمتة: auto-arm عند عدم الرد، تشغيل الـ Speaker، التسليم الذكي)، `QuickVoiceRecorder` (MediaRecorder بحد أقصى)، `QuickVoicePlayer` (تشغيل المقاطع الواردة). |
| `core:data` | `SettingsRepository` (DataStore)، `ContactsRepository` (دليل الهاتف + ربط VoIP ID)، `CallHistoryRepository` (سجل المكالمات Room). |
| `feature:home` | تبويبات Contacts / Dial Pad / Recent Calls، بطاقات الأذونات و default dialer، مفتاح Quick Voice. |
| `feature:call` | شاشة المكالمة: الاسم، الحالة، المؤقّت، Speaker/Mic/End، وزر Quick Voice. |
| `feature:settings` | إعدادات Quick Voice (المدة 2–5 ث، auto-arm، speaker تلقائي)، إعدادات VoIP (عنوان الخادم)، الأذونات، دور الهاتف. |

### تقنيات البناء

- **Kotlin + Jetpack Compose (Material 3)**
- **Hilt** للـ Dependency Injection
- **Coroutines + StateFlow** لتدفق الحالة
- **MVVM / Clean Architecture** بفصل وحدات واضح
- **Room + DataStore** للتخزين المحلي
- **WebRTC** (مكتبة `webrtc-sdk`) لمكالمات الإنترنت مع:
  - Echo Cancellation, Noise Suppression, AGC (عبر `MediaConstraints` + معالجة WebRTC)
  - إعادة اتصال ICE عند ضعف الشبكة / تغيّر النقل (Wi-Fi ⇄ بيانات)

---

## 3. كيف تعمل ميزة Quick Voice

1. **تفعيل** الوضع من الشاشة الرئيسية (أو الإعدادات).
2. عند إجراء مكالمة:
   - **VoIP**: زر Quick Voice متاح فورًا (رنين أو نشطة).
   - **SIM غير مجاب عنها**: بعد `autoActivateAfterMs` (افتراضي 15 ثانية) يتسلّح الوضع تلقائيًا ويُشغَّل الـ Speaker تلقائيًا (حسب الإعداد).
3. **اضغط واستمر** على زر الميكروفون → يبدأ التسجيل مع مؤشر دائري (Recording + elapsed).
4. **ارفع إصبعك** (أو يصل الحد الأقصى 2–5 ثوانٍ) → ينتهي التسجيل تلقائيًا.
5. **التسليم الذكي**:
   - مكالمة VoIP حيّة مع DataChannel مفتوح → يُرسَل فورًا عبر WebRTC DataChannel (بدون مرور بالخادم).
   - VoIP غير نشط / الطرف غير متصل → رسالة صوتية عبر خادم الإشارة (تُخزَّن مؤقتًا إذا كان غير متصل).
   - SIM مع ربط VoIP للطرف → رسالة صوتية.
   - SIM بدون ربط → تشغيل بصوت عالٍ على الـ Speaker + تنبيه صادق.

لا تُحفظ أي تسجيلات: الملفات تُكتب في `cacheDir` وتُحذف فور التسليم.

---

## 4. تشغيل المشروع

### المتطلبات
- Android Studio (أو Gradle 8.11+، JDK 17)
- SDK Platform 35 (`local.properties` → `sdk.dir`)

### البناء
```bat
gradlew.bat assembleDebug
:: أو عبر gradle مباشرة
gradle assembleDebug
```
النتيجة: `app/build/outputs/apk/debug/app-debug.apk`

### خادم الإشارة (لمكالمات Wi-Fi)
```bat
cd server
npm install
npm start
```
يعمل على `ws://<ip>:8080/signaling`. ثم في التطبيق: **Settings → Wi-Fi calls → Signaling server** ضع العنوان.
الخادم يدعم: تسجيل المستخدمين، ترحيل `call/answer/offer/ice/hangup/decline`، فحص الحضور، وصندوق بريد مؤقت للمقاطع عندما يكون الطرف غير متصل. (اختبار: `node smoke-test.js`)

### الحالة 1: الجهازان على نفس شبكة الوايفاي
1. شغّل الخادم على أحد الأجهزة (أو على حاسوب على نفس الشبكة).
2. في التطبيق على **الجهازين**: `Settings → Wi-Fi calls → Signaling server` = `ws://<ip-محلي>:8080/signaling` ثم **Save** ثم **Connect** حتى تظهر الحالة `Registered (ready)`.
3. لا حاجة لـ TURN — الجهازان يرتبطان مباشرة عبر الشبكة المحلية.
4. في شاشة الطلب: بدّل النوع إلى **Wi-Fi** وأدخل **User ID** الخاص بالطرف الآخر (يظهر بعد أول اتصال) ثم اتصل.

### الحالة 2: الجهازان على شبكتين مختلفتين (إنترنت)
المشكلة: خادم الإشارة يجب أن يكون **متاحًا من الإنترنت**، ومسار الصوت WebRTC يحتاج **TURN** (بدونه تفشل المكالمة غالبًا لأن نقاط NAT على شبكات الجوال من نوع symmetric).

**الخطوة أ — الخادم متاح من الإنترنت** (اختر واحدة):
- **VPS**: انسخ `server/` إلى سيرفر عام (أبسط: `npm install && npm start`)، وافتح المنفذ 8080 في جدار الحماية. العنوان يصبح `ws://<ip-عام>:8080/signaling`.
- **نفق مؤقت (بدون سيرفر)**: مع تثبيت `cloudflared` على جهازك، شغّل الخادم محليًا ثم:
  ```bat
  cloudflared tunnel --url http://localhost:8080
  ```
  يعطيك رابطًا عامًا مثل `https://xxx.trycloudflare.com` → استخدم `wss://xxx.trycloudflare.com/signaling` في التطبيق. (الرابط يتغيّر كل إعادة تشغيل، مناسب للاختبار فقط.)

**الخطوة ب — TURN** (لا بد منه لمسار الصوت بين الشبكات). في `Settings → Wi-Fi calls → TURN`:
- **للتجربة**: استخدم خدمة مجتمعية مجانية:
  - TURN URL: `turn:openrelay.metered.ca:80`
  - username: `openrelayproject` · password: `openrelayproject`
- **للإنتاج**: ثبّت **coturn** على نفس الـ VPS الخاص بالخادم (`turnserver --user quickvoice:password --realm quickvoice --fingerprint`) ثم أدخل `turn:<ip-vps>:3478` وبيانات الاعتماد في التطبيق.
- انقر **Save** ثم **Reconnect** حتى تنعكس الإعدادات على الاتصال.

### على الجهاز (لأفضل تجربة SIM)
1. امنح الأذونات (الميكروفون، جهات الاتصال، الهاتف، الإشعارات).
2. اجعل التطبيق **تطبيق الهاتف الافتراضي** من الإعدادات — هذا ضروري لكي تبقى شاشة المكالمة داخل التطبيق ويتم التحكم بالحالة والـ Speaker أثناء مكالمات SIM.

---

## 5. الصلاحيات وسبب كل منها

| الصلاحية | السبب |
|---|---|
| `RECORD_AUDIO` | تسجيل مقاطع Quick Voice + مكالمات VoIP. |
| `READ_CONTACTS` | الاتصال من دليل الهاتف وربط VoIP ID بجهة الاتصال. |
| `CALL_PHONE` | إجراء مكالمات SIM عبر Telecom. |
| `POST_NOTIFICATIONS` (أندرويد 13+) | إشعارات المكالمات الواردة عبر VoIP. |
| `INTERNET` / `ACCESS_NETWORK_STATE` / `ACCESS_WIFI_STATE` | مكالمات Wi-Fi / VoIP ومراقبة الشبكة لإعادة ICE. |
| `MODIFY_AUDIO_SETTINGS` | توجيه صوت VoIP (سماعة / speaker). |
| `FOREGROUND_SERVICE*` | خدمة أمامية تبقّي مكالمة VoIP حيّة أثناء التشغيل أو عند استقبال مكالمة. |
| دور **default dialer** | استقبال `InCallService` والتحكم بمكالمات SIM (حالة، Speaker، رد/رفض). |

---

## 6. الخصوصية والأمان

- لا يُسجَّل أي صوت بشكل دائم؛ الملفات المؤقتة تُحذف بعد الإرسال.
- سجل المكالمات وروابط VoIP مخزنة محليًا فقط (Room/DataStore).
- لا تُرسل أي بيانات لخوادم خارجية: خادم الإشارة هو الخادم الذي يحدده المستخدم بنفسه، ولا يُستعمل إلا لترحيل الإشارات/الرسائل.
- التطبيق لا يمتلك أي Accessibility hooks ولا أي وصول "قذر" للنظام.

---

## 7. ما هو محدود بصراحة (وما هي البدائل)

| المطلوب | الحقيقة على Android | البديل المُنفَّذ |
|---|---|---|
| إرسال صوت عبر مكالمة SIM بدون فتح الواجهة | ممنوع (لا API لإدخال صوت في المكالمة) | رسالة صوتية عبر VoIP للطرف المستخدم للتطبيق، أو تشغيل بصوت عالٍ + تنبيه. |
| التحكم بـ Speaker أثناء مكالمة SIM | ممكن للـ default dialer فقط، وبقيود `MODIFY_AUDIO_ROUTING` على Android 14+ | `setCommunicationDevice` مع fallbacks وإعلام المستخدم بدور الهاتف الافتراضي. |
| استقبال حالة مكالمة SIM في تطبيق غير افتراضي | غير ممكن | طلب دور default dialer بشكل واضح + عودة إلى تطبيق الهاتف عند غياب الدور. |
| مكالمات Wi-Fi بدون خادم إشارة | غير ممكنة (يحتاج WebRTC خادم إشارة) | خادم Node.js بسيط ذاتي الاستضافة في `server/`. |
| استقبال مكالمة VoIP أثناء خلفية التطبيق | محدود: لا يمكن لخدمة أمامية أن تُقلع من الخلفية على Android 12+، وبدون نظام دفع (push) لا يوقظ الخادم عملية مقتولة | عند فتح التطبيق تُعرض المكالمة الواردة على الشاشة. للإنتاج الكامل: أضف **FCM push** (لإيقاظ التطبيق) أو سجّل **Telecom `ConnectionService` + `PhoneAccount`** لتظهر مكالمات VoIP كمكالمات نظام. |
