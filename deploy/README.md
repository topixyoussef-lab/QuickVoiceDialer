# النشر العالمي 24/7 (Global deployment)

لتشغيل الخادم على الإنترنت دائمًا، تحتاج **VPS** (سيرفر افتراضي عام). أمثلة رخيصة/مجانية:
**Oracle Cloud Free Tier** (مجاني مدى الحياة، VM صغير يكفي تمامًا)، **Hetzner** (~4$/شهر)،
**DigitalOcean** (~4$/شهر)، **AWS Lightsail**.

> لماذا VPS وليس جهازك؟ خادم 24/7 يحتاج: عنوان IP عام ثابت، اتصال إنترنت مستقر، وجهاز يعمل
> باستمرار. الـ VPS يوفر ذلك بدولارات قليلة. التطبيق يحتاج عنوان `wss://` عامًا ليستخدمه الجميع.

## الخطوات

### 1) DNS
أضف سجل `A` في مسجل الدومين الخاص بك، مثل:
```
signal.yourdomain.com  →  <IP العام لخادمك>
```
(إن لم يكن لديك دومين، استخدم خدمة DDNS مثل DuckDNS: `signal.xxxx.duckdns.org`.)

### 2) الدخول للخادم ورفع الملفات
```bash
scp -r deploy server root@<server-ip>:/opt/quickvoice/
ssh root@<server-ip>
```

### 3) الخيار أ — Docker (موصى به: يشمل TURN + HTTPS تلقائيًا)
```bash
cd /opt/quickvoice/deploy
# عدّل coturn.conf  (user=quickvoice:<كلمة سر قوية>)
# عدّل Caddyfile  (ضع دومينك مكان signal.example.com)
docker compose up -d --build
docker compose logs -f
```
- إشارة WebSocket: `wss://signal.yourdomain.com/signaling`
- TURN: `turn:signal.yourdomain.com:3478` + المستخدم/كلمة السر من `coturn.conf`
- فحص النسخة: `https://signal.yourdomain.com/api/version`

افتح في جدار الحماية: `80/tcp`, `443/tcp`, `3478/udp`, `3478/tcp`, `49152:65535/udp`.

### 4) الخيار ب — بدون Docker (Node مباشر + systemd)
```bash
cd /opt/quickvoice/deploy
sudo bash install.sh
```
ثم ضع nginx أو caddy أمام المنفذ 8080 للـ HTTPS. مثال nginx:
```nginx
server {
    server_name signal.yourdomain.com;
    location / { proxy_pass http://127.0.0.1:8080; proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; }
    location /signaling { proxy_pass http://127.0.0.1:8080; proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade; proxy_set_header Connection "upgrade"; }
}
```
ثم `certbot --nginx`.

### 5) نشر نسخة جديدة من التطبيق (التحديث التلقائي)
1. ابنِ الـ APK الجديد.
2. ضعه في مجلد الإصدارات على الخادم:
   ```bash
   cp QuickVoiceDialer.apk /opt/quickvoice/server/releases/
   ```
3. حدّث `releases/version.json` (ارفع versionCode رقميًا في كل نسخة):
   ```json
   { "versionName": "1.0.1", "versionCode": 2, "apkUrl": "/apk/QuickVoiceDialer.apk" }
   ```
4. التطبيقات المثبتة سترى التحديث تلقائيًا (فحص دوري) وتطلب تثبيت النسخة الجديدة.

## ضبط التطبيق بعد النشر
في التطبيق على كل جهاز:
- **Signaling server**: `wss://signal.yourdomain.com/signaling`
- **TURN URL**: `turn:signal.yourdomain.com:3478` (+ اسم/كلمة السر من coturn.conf)
- احفظ ثم Connect حتى تظهر `Registered (ready)`.

## ملاحظات
- النفق المؤقت (cloudflared trycloudflare) للاختبار فقط، وليس 24/7.
- مفاتيح TURN والاتصال تُخزَّن محليًا على الجهاز فقط.
