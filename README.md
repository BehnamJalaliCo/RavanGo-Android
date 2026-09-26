<div dir="rtl">

# روان‌گو | RavanGo

**پلتفرم کامل تولید محتوای ویدیویی برای اندروید** — از نوشتن اسکریپت تا ضبط، بیوتی، صدا، تدوین، زیرنویس، هوش مصنوعی و خروجی نهایی، بدون خروج از اپ.

تله‌پرامپتر حرفه‌ای · دوربین حرفه‌ای · بیوتی و میکاپ لحظه‌ای · ادیتور ویدیو · استودیوی هوش مصنوعی · حساب کاربری و همگام‌سازی ابری

[![Android CI](https://github.com/BehnamJalaliCo/RavanGo-Android/actions/workflows/android.yml/badge.svg)](https://github.com/BehnamJalaliCo/RavanGo-Android/actions/workflows/android.yml)

</div>

---

## فهرست / Contents

<div dir="rtl">

1. [دریافت APK](#دریافت-apk)
2. [قابلیت‌ها](#قابلیتها)
3. [معماری و تکنولوژی](#معماری-و-تکنولوژی)
4. [ساخت پروژه](#ساخت-پروژه)
5. [سرویس‌های خارجی و پیکربندی](#سرویسهای-خارجی-و-پیکربندی)
6. [CI/CD و انتشار](#cicd-و-انتشار)
7. [مدل درآمدی](#مدل-درآمدی)
8. [حریم خصوصی و مجوزها](#حریم-خصوصی-و-مجوزها)
9. [فونت‌ها و مجوزها (لایسنس‌ها)](#فونتها-و-مجوزها-لایسنسها)
10. [تست و کیفیت](#تست-و-کیفیت)
11. [محدودیت‌های شناخته‌شده](#محدودیتهای-شناختهشده)
12. [English summary](#english-summary)

</div>

---

<div dir="rtl">

## دریافت APK

هر push روی شاخه‌ی اصلی، به‌صورت خودکار در GitHub Actions ساخته می‌شود و فایل‌ها در بخش **Releases → `latest-build`** منتشر می‌شوند:

| فایل | کاربرد |
|---|---|
| `RavanGo-release-arm64-v8a.apk` | **اکثر گوشی‌ها** (۶۴ بیتی) — نسخه‌ی بهینه‌شده (R8) |
| `RavanGo-release-armeabi-v7a.apk` | گوشی‌های قدیمی ۳۲ بیتی |
| `RavanGo-release-universal.apk` | اگر مطمئن نیستید؛ روی همه‌ی دستگاه‌ها نصب می‌شود (حجم بیشتر) |
| `RavanGo-release-x86_64.apk` | شبیه‌ساز / دستگاه‌های x86 |
| `RavanGo-release.aab` | App Bundle برای انتشار در Google Play |
| `RavanGo-debug-universal.apk` | نسخه‌ی اشکال‌زدایی (قابل نصب کنار نسخه‌ی release با شناسه‌ی `com.ravango.app.debug`) |

برای نسخه‌های رسمی، یک tag به شکل `v1.0.0` push کنید تا Release با همان نام ساخته شود.

> بدون کلید امضای release (به بخش CI مراجعه کنید)، APK نسخه‌ی release با کلید اشکال‌زدایی مشترک پروژه امضا می‌شود؛ برای نصب مستقیم مناسب است ولی برای Google Play نه.

**نیازمندی دستگاه:** اندروید ۸٫۰ (API 26) به بالا، OpenGL ES 2.0 به بالا. قابلیت‌های سنگین (4K، 60fps، بیوتی کامل) بر اساس توان دستگاه فعال یا تطبیق داده می‌شوند.

---

## قابلیت‌ها

### ۱. تله‌پرامپتر (`engine:teleprompter`، `feature:teleprompter`)
- اسکرول **مبتنی بر زمان و دقیق فریم** (Choreographer) بدون پرش؛ سرعت بر حسب **کلمه در دقیقه** (مستقل از اندازه‌ی فونت).
- فونت، اندازه، وزن، رنگ، فاصله‌ی خطوط و حروف، عرض ناحیه‌ی متن، ترازبندی (شامل justify)، فاصله از لبه، جهت متن (خودکار/راست‌به‌چپ/چپ‌به‌راست).
- موقعیت متن نزدیک لنز (بالا/وسط/پایین)، **خط چشم** قابل تنظیم، کم‌رنگ شدن متن خوانده‌شده، شفافیت پس‌زمینه.
- **آینه‌ی افقی و عمودی** (مناسب ریگ‌های beam-splitter)، شمارش معکوس، توقف/ادامه، تکرار (Loop)، شروع از هر نقطه یا بخش، نمایش زمان باقی‌مانده و پیشرفت.
- نشانه‌گذاری اسکریپت: `## بخش`، `==هایلایت==`، `**تأکید**`، `[مکث]`، `[[یادداشت کارگردان]]` (از زمان‌بندی حذف می‌شود).
- کنترل با لمس، کشیدن، pinch (اندازه‌ی فونت)، **دکمه‌های صدا** و **ریموت بلوتوثی / کیبورد / page-turner**.
- حالت عمودی و افقی، **تله‌پرامپتر شناور** روی اپ‌های دیگر (سرویس پیش‌زمینه + پنجره‌ی overlay، قابل جابه‌جایی و تغییر اندازه)، استفاده هم‌زمان با دوربین جلو و عقب.
- پریست‌های سرعت و ظاهر (پیش‌فرض و شخصی) و **ذخیره‌ی تنظیمات مخصوص هر اسکریپت**.

### ۲. اسکریپت‌ها (`feature:scripts`)
- ایجاد، ویرایش (ذخیره‌ی خودکار، undo/redo، نوار ابزار نشانه‌گذاری، پیش‌نمایش)، کپی، پوشه‌بندی با رنگ، علاقه‌مندی، جستجوی حساس به حروف فارسی/عربی و نیم‌فاصله.
- **Import** از `.txt`، `.md`، `.docx`، `.rtf`، `.html`، `.srt`، `.vtt` و کلیپ‌بورد (تشخیص encoding شامل Windows-1256). *PDF پشتیبانی نمی‌شود و پیام راهنما نمایش داده می‌شود.*
- دستیار هوش مصنوعی داخل ویرایشگر (بازنویسی، کوتاه/بلند کردن، لحن، اصلاح نگارش، ترجمه، هوک، CTA…).

### ۳. استودیوی دوربین (`engine:camera`، `feature:camera`)
- **Camera2** مستقیم با **تشخیص دقیق توانایی‌های سخت‌افزار**: فقط رزولوشن‌ها و FPSهایی نمایش داده می‌شوند که دوربین و انکودر دستگاه واقعاً پشتیبانی می‌کنند (720p تا 4K و بالاتر؛ 24/25/30/48/50/60 و بیشتر).
- دوربین جلو/عقب، **تعویض لنز** (فوق‌عریض/عریض/تله و زوم 0.5x روی دوربین‌های منطقی)، زوم، فوکوس دستی و خودکار، **Exposure**، **ISO**، **Shutter**، **White Balance** (پریست‌ها و Kelvin)، قفل AE/AF، Stabilization، HDR (در صورت پشتیبانی)، فلاش و فلاش صفحه برای دوربین جلو.
- Grid، تراز (Level)، تایمر، نسبت تصویر (9:16، 16:9، 1:1، 4:5، 3:4، 4:3، 21:9)، Safe Area، نمایش فضای ذخیره‌سازی و **زمان قابل ضبط**.
- خط لوله‌ی GPU واحد: پیش‌نمایش و فایل ضبط‌شده دقیقاً یکسان‌اند؛ اولویت همیشه با انکودر است و در صورت کندی، فریم پیش‌نمایش حذف می‌شود نه فریم ضبط.
- ضبط **قطعه‌قطعه (segmented)** و **بازیابی پس از کرش**؛ توقف امن هنگام کمبود فضا یا دمای بحرانی.
- تله‌پرامپتر روی دوربین (هم‌گام با شروع/توقف ضبط)، پنل بیوتی و پنل صدا در همان صفحه. پس از ضبط، پروژه ساخته و **ادیتور** باز می‌شود.

### ۴. صدا (`engine:audio`، `core:media/dsp`)
- حالت‌های تصویر+صدا، فقط صدا، تصویر بدون صدا.
- انتخاب ورودی: میکروفون داخلی، سیمی، **USB**، **بلوتوث (SCO/LE)** و سایر ورودی‌هایی که اندروید ارائه می‌دهد (با تغییر زنده هنگام اتصال/جدا شدن).
- **Gain دیجیتال**، **متر زنده** (Peak/RMS)، **تشخیص Clipping**، **حذف نویز طیفی** (FFT اختصاصی)، **Voice Enhancement** (EQ + کمپرسور)، High-pass، لیمیتر، **مانیتورینگ** با هدفون سیمی/USB.
- ضبط فقط‌صدا به AAC/M4A با ژورنال ضدکرش.

### ۵. بیوتی و میکاپ لحظه‌ای (`engine:beauty`، `feature:beauty`)
- **Beauty:** صاف کردن پوست، رتوش، حذف لک و جوش، روشنایی، رنگ پوست، شارپ، سفید کردن، حذف تیرگی زیر چشم، لاغری صورت، فک، چانه، گونه، پیشانی، بینی، اندازه و فرم چشم، سفید کردن دندان.
- **Makeup:** رژ لب، رنگ لب، ابرو، مژه، خط چشم، سایه چشم، رژگونه، کانتور، هایلایت، کرم‌پودر — هرکدام با اسلایدر ۰ تا ۱۰۰ و انتخاب رنگ.
- ردیابی صورت روی دستگاه (ML Kit) + فیلتر One-Euro برای حرکت نرم، شیدرهای GLES روی پیش‌نمایش **و** فایل نهایی.
- **Before/After**، پریست‌های قابل ذخیره، **کاهش هوشمند کیفیت** بر اساس توان دستگاه، دما و زمان واقعی هر فریم (FULL → BALANCED → LIGHT → MINIMAL) تا FPS ضبط حفظ شود.

### ۶. ادیتور ویدیو (`engine:editor`، `feature:editor`)
- **Timeline چندلایه** (ویدیو اصلی، لایه‌های Overlay، موسیقی، Voice-over، زیرنویس)، زوم، snap با لرزش، دستگیره‌های Trim، جابه‌جایی کلیپ‌ها.
- Trim، Split، Cut، Merge، Crop، Rotate، Flip، Resize، **Speed**، **Reverse**، **Freeze Frame**، Transition، فیلترها و تنظیم رنگ کامل (Exposure، Contrast، Highlights، Shadows، Saturation، Temperature، Tint، Sharpen، Blur، Vignette…).
- متن، استیکر، Overlay تصویری، **Picture-in-Picture**، لوگو/واترمارک، موسیقی، Voice-over، حجم صدا، Fade In/Out، استخراج صدا، حذف نویز.
- **زیرنویس خودکار** و **ویرایشگر زیرنویس** (فونت، رنگ، رنگ کلمه‌ی فعال، پس‌زمینه، موقعیت، انیمیشن karaoke/کلمه‌به‌کلمه)، ورود/خروج SRT.
- Canvas و پس‌زمینه (رنگ، گرادیان، بلور)، نسبت‌های 9:16، 16:9، 1:1، 4:5 و سفارشی.
- **Undo/Redo** کامل (۱۰۰ مرحله)، **ذخیره‌ی خودکار پیش‌نویس**، **Export** با انتخاب رزولوشن، FPS، Bitrate و کدک در سرویس پیش‌زمینه.

### ۷. استودیوی هوش مصنوعی (`engine:ai`، `feature:ai`)
- تولید اسکریپت از موضوع، بازنویسی، کوتاه/بلند کردن، تغییر لحن، اصلاح نگارش، Hook، CTA، عنوان، کپشن، توضیحات، ایده‌ی محتوا، **ترجمه‌ی فارسی ↔ انگلیسی**، خلاصه‌سازی، Bullet به Script، رسمی به محاوره‌ای.
- **Auto Subtitle / تشخیص گفتار** (گیت‌وی Whisper، OpenAI، یا تشخیص گفتار روی دستگاه در اندروید ۱۳+)، **تشخیص و حذف سکوت** (روی دستگاه)، تشخیص کلمات پرکننده و تکرار، **Highlight**، **پیشنهاد Cut**، **پیشنهاد Short/Reel** از ویدیوی بلند، **تدوین خودکار**، **پاک‌سازی صدا**.
- **Eye Contact Correction:** نیازمند مدل سمت سرور است؛ رابط کامل آماده است و وضعیت «نیازمند سرویس» صادقانه نمایش داده می‌شود.
- **Provider قابل تعویض:** گیت‌وی روان‌گو (پیش‌فرض، کلید API هرگز داخل APK نیست)، Anthropic Claude با کلید شخصی، یا هر سرویس سازگار با OpenAI. مدل پیش‌فرض `claude-opus-5` از طریق SDK رسمی Anthropic.
- ارسال محتوا به سرویس ابری **فقط با رضایت صریح کاربر**.

### ۸. حساب کاربری و ابر (`platform:auth`، `platform:cloud`، `feature:account`)
- ورود با **کد ایمیل**، **ایمیل و رمز**، **کد پیامکی** (نرمال‌سازی شماره‌های ایرانی) و **Google** (Credential Manager). حالت مهمان بدون محدودیت در قابلیت‌های محلی.
- **همگام‌سازی Local-first:** اسکریپت‌ها، پوشه‌ها، پروژه‌ها، پیش‌نویس‌ها، پریست‌های بیوتی و تله‌پرامپتر، تنظیمات. آفلاین کامل کار می‌کند و پس از اتصال همگام می‌شود (WorkManager). در تعارض ویرایش اسکریپت، نسخه‌ی «کپی تعارض» ساخته می‌شود تا متنی گم نشود.
- پشتیبان‌گیری ویدیو (Pro) با آپلود قابل ادامه (TUS).
- پروفایل، تنظیمات (زبان، تم، لرزش، کاهش حرکت، کیفیت خروجی پیش‌فرض، Provider هوش مصنوعی)، مدیریت ابر، حریم خصوصی، شرایط استفاده، درباره، **حذف حساب**، خروجی گرفتن از داده‌ها.

### ۹. صفحه‌ی اصلی، آشنایی و پروژه‌ها
- **Home:** ضبط جدید، ضبط صدا، تله‌پرامپتر، اسکریپت‌ها، AI Studio، ادیتور ویدیو، پروژه‌ها، پیش‌نویس‌ها، قالب‌ها، ابر، پروژه‌ها و اسکریپت‌های اخیر.
- **Onboarding** کوتاه (۴ مرحله: زبان، معرفی، حریم خصوصی) — **هیچ مجوزی در Onboarding درخواست نمی‌شود**؛ همه‌ی مجوزها دقیقاً زمان نیاز و با توضیح شفاف گرفته می‌شوند.
- **قالب‌ها:** ریلز اینستاگرام، ویدیوی یوتیوب، Short، لینکدین، معرفی محصول، آموزش، پادکست.

### طراحی
رابط پاستیلی و مینیمال با گرادیان‌های ملایم، سطوح شیشه‌ای، گوشه‌های نرم، micro-interaction و بازخورد لمسی، انیمیشن‌های فنری، حالت روشن و تیره و احترام به تنظیم «کاهش حرکت». **فارسی زبان اصلی** و انگلیسی کامل؛ RTL/LTR در همه‌ی صفحات، ادیتور، تله‌پرامپتر و تنظیمات.

---

## معماری و تکنولوژی

| بخش | انتخاب |
|---|---|
| زبان / UI | Kotlin 2.2، Jetpack Compose با Design System اختصاصی |
| معماری | ماژولار چندلایه، MVVM/UDF، Hilt |
| داده | Room (local-first)، DataStore، SecureStore مبتنی بر Android Keystore |
| دوربین و GPU | Camera2 + EGL14/GLES2-3 + MediaCodec + MediaMuxer |
| بیوتی | ML Kit Face Detection (روی دستگاه) + شیدرهای GLSL |
| ادیتور | Media3 Transformer / Effect / CompositionPlayer 1.8 |
| هوش مصنوعی | Anthropic Java SDK (Claude)، گیت‌وی Supabase Edge Function |
| بک‌اند | Supabase (Auth، PostgREST با RLS، Storage) |
| پرداخت | Google Play Billing 8 (قابل جایگزینی با بازار/مایکت) |

```
app ──► feature:* ──► engine:* / platform:* ──► core:*
```

| لایه | ماژول‌ها |
|---|---|
| core | `model` · `common` · `designsystem` · `ui` · `navigation` · `database` · `datastore` · `data` · `media` |
| engine | `render` · `camera` · `audio` · `beauty` · `teleprompter` · `editor` · `ai` |
| platform | `auth` · `cloud` · `billing` |
| feature | `onboarding` · `home` · `scripts` · `teleprompter` · `camera` · `beauty` · `editor` · `ai` · `projects` · `account` · `paywall` |

جزئیات کامل در [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).

---

## ساخت پروژه

**پیش‌نیازها:** JDK 17 یا بالاتر، Android SDK (platform 36، build-tools 35+). Gradle Wrapper همراه پروژه است.

```bash
git clone https://github.com/BehnamJalaliCo/RavanGo-Android.git
cd RavanGo-Android
echo "sdk.dir=$ANDROID_HOME" > local.properties      # یا با Android Studio باز کنید

./gradlew :app:assembleDebug          # APK اشکال‌زدایی
./gradlew :app:assembleRelease        # APK بهینه‌شده (R8)
./gradlew testDebugUnitTest           # همه‌ی تست‌های واحد
```

خروجی‌ها: `app/build/outputs/apk/`.

**فونت برند (رواق):** فایل‌های فونت به‌صورت رمزنگاری‌شده در `fonts-private/` هستند. برای build محلی با فونت رواق:

```bash
mkdir -p private/fonts/ravagh
gpg --batch --pinentry-mode loopback --passphrase "<RAVAGH_FONT_PASSPHRASE>" \
    -d fonts-private/ravagh-fonts.tar.gz.gpg | tar -xz -C private/fonts/ravagh
```

بدون این مرحله، build به‌صورت خودکار از فونت آزاد وزیرمتن استفاده می‌کند.

---

## سرویس‌های خارجی و پیکربندی

اپ **بدون هیچ سرویس خارجی کاملاً کار می‌کند** (تله‌پرامپتر، ضبط، بیوتی، ادیتور، سکوت‌یابی و پاک‌سازی صدا روی دستگاه). هر قابلیتی که به سرویس نیاز دارد، در صورت پیکربندی نشدن، دقیقاً اعلام می‌کند چه سرویسی لازم است.

مقادیر از این ترتیب خوانده می‌شوند: متغیر محیطی `RAVANGO_*` ← `secrets.properties` (در git نیست) ← `secrets.defaults.properties`.

| کلید | متغیر CI | کاربرد |
|---|---|---|
| `supabaseUrl` | `RAVANGO_SUPABASE_URL` | حساب کاربری، همگام‌سازی، پشتیبان ویدیو |
| `supabaseAnonKey` | `RAVANGO_SUPABASE_ANON_KEY` | کلید عمومی Supabase (هرگز service_role) |
| `googleWebClientId` | `RAVANGO_GOOGLE_WEB_CLIENT_ID` | ورود با گوگل |
| `aiGatewayUrl` | `RAVANGO_AI_GATEWAY_URL` | گیت‌وی هوش مصنوعی و تشخیص گفتار |
| `distribution` | `RAVANGO_DISTRIBUTION` | `play` / `bazaar` / `myket` / `direct` |

| سرویس | راه‌اندازی |
|---|---|
| **Supabase** (Auth + DB + Storage) | اجرای [`backend/sql/schema.sql`](backend/sql/schema.sql) — راهنما: [`backend/README.md`](backend/README.md) |
| **گیت‌وی هوش مصنوعی** | Edge Function در [`backend/functions/ai-gateway`](backend/functions/ai-gateway) + [`backend/sql/ai.sql`](backend/sql/ai.sql)؛ متغیرها: `ANTHROPIC_API_KEY`، `WHISPER_API_URL`، `WHISPER_API_KEY` — راهنما: [`backend/README-ai.md`](backend/README-ai.md) |
| **Google Play Billing** | محصولات `ravango_pro` (monthly/yearly)، `ravango_lifetime`، `ai_credits_200`، `ai_credits_1000` |
| **پیامک (OTP)** | ارائه‌دهنده‌ی SMS در Supabase (مثلاً Kavenegar از طریق Send SMS hook) |
| **Eye Contact** (اختیاری) | `EYE_CONTACT_API_URL` و `EYE_CONTACT_API_KEY` در گیت‌وی |
| **کافه‌بازار / مایکت** | نیازمند SDK آن‌ها (Poolakey / Myket IAB)؛ رابط `BillingProvider` آماده است |

---

## CI/CD و انتشار

Workflow: [`.github/workflows/android.yml`](.github/workflows/android.yml)

1. رمزگشایی فونت رواق (در صورت وجود secret)
2. تست‌های واحد همه‌ی ماژول‌ها
3. ساخت APKهای debug و release
4. Lint
5. آپلود Artifact و انتشار در Release `latest-build` (و Releaseهای tag‌دار `v*`)

**Secretهای مخزن** (Settings → Secrets and variables → Actions):

| Secret | الزامی | توضیح |
|---|---|---|
| `RAVAGH_FONT_PASSPHRASE` | برای فونت رواق | کلید رمزگشایی `fonts-private/ravagh-fonts.tar.gz.gpg` |
| `RAVANGO_RELEASE_KEYSTORE_BASE64` | برای انتشار در Play | keystore انتشار به‌صورت base64 |
| `RAVANGO_RELEASE_STORE_PASSWORD`، `RAVANGO_RELEASE_KEY_ALIAS`، `RAVANGO_RELEASE_KEY_PASSWORD` | همراه keystore | |
| `RAVANGO_SUPABASE_URL`، `RAVANGO_SUPABASE_ANON_KEY`، `RAVANGO_GOOGLE_WEB_CLIENT_ID`، `RAVANGO_AI_GATEWAY_URL` | اختیاری | فعال‌سازی سرویس‌های ابری در APK |

---

## مدل درآمدی

بدون تبلیغات. جزئیات کامل: [`docs/MONETIZATION.md`](docs/MONETIZATION.md)

| | رایگان | Pro (ماهانه/سالانه با ۷ روز آزمایشی) | مادام‌العمر |
|---|---|---|---|
| تله‌پرامپتر | کامل (به‌جز شناور) | + شناور | مانند Pro |
| ضبط | 1080p · 30fps | 4K · 60fps · HEVC، کنترل دستی | مانند Pro |
| بیوتی | پایه | + پیشرفته، فرم صورت، میکاپ | مانند Pro |
| ادیتور و خروجی | پایه، ≤1080p با واترمارک کوچک | کامل، 4K/60 بدون واترمارک | مانند Pro |
| هوش مصنوعی | ۲۰ اعتبار در ماه | زیرنویس خودکار، ابزار ویدیویی، ۱۰۰۰ اعتبار | ابزار ویدیویی، ۲۰۰ اعتبار |
| ابر | اسکریپت‌ها و تنظیمات | + پروژه‌ها و ۵۰ گیگابایت ویدیو | + پروژه‌ها و ۲۰ گیگابایت |

بسته‌های اعتبار هوش مصنوعی (۲۰۰ و ۱۰۰۰) برای همه. همه‌ی قوانین به‌صورت داده تعریف شده‌اند و از راه دور (جدول `app_config`) قابل تغییرند.

---

## حریم خصوصی و مجوزها

- دوربین، میکروفون، بلوتوث و اعلان‌ها **فقط هنگام نیاز** و با توضیح شفاف درخواست می‌شوند؛ Onboarding هیچ مجوزی نمی‌گیرد.
- پردازش تصویر، بیوتی، صدا، سکوت‌یابی و پاک‌سازی صدا **روی دستگاه** انجام می‌شود.
- هیچ داده‌ای بدون اجازه ارسال نمی‌شود: همگام‌سازی ابری خاموش است تا کاربر فعالش کند؛ ارسال متن/صدا به هوش مصنوعی نیازمند رضایت است؛ آمار و گزارش کرش پیش‌فرض خاموش‌اند.
- توکن‌ها و کلیدهای API در Android Keystore رمز می‌شوند و از پشتیبان‌گیری مستثنا هستند.
- صفحات **حریم خصوصی**، **شرایط استفاده**، **مدیریت و حذف حساب** و **خروجی داده‌ها** در اپ موجودند.

---

## فونت‌ها و مجوزها (لایسنس‌ها)

| مورد | مجوز |
|---|---|
| **فونت رواق (Ravagh)** — فونت برند | تجاری، خریداری‌شده از [fontiran.com](https://fontiran.com)، **شماره‌ی لایسنس ۱۳۶۷۱۰**. فقط برای نمایش رابط کاربری در اپ جاسازی شده است؛ کپی، استخراج، بازنشر یا تغییر فایل‌ها مجاز نیست. فایل‌ها در این مخزن عمومی فقط به‌صورت رمزنگاری‌شده نگهداری می‌شوند. ([شرایط](https://fontiran.com/about-licenses)) |
| وزیرمتن، ساحل، صمیم (صابر راستی‌کردار) | [SIL Open Font License 1.1](docs/licenses/) |
| کتابخانه‌های متن‌باز | فهرست کامل و متن مجوزها: صفحه‌ی **«مجوزها»** داخل اپ (تولید خودکار با AboutLibraries) و [`docs/THIRD_PARTY_LICENSES.md`](docs/THIRD_PARTY_LICENSES.md) |

کد منبع روان‌گو: © RavanGo — همه‌ی حقوق محفوظ است.

---

## تست و کیفیت

- تست‌های واحد JVM برای منطق خالص: پارسر و زمان‌بندی تله‌پرامپتر، DSP صدا (FFT، حذف نویز، لیمیتر، سکوت‌یابی)، انتخاب رزولوشن/FPS و محاسبات دوربین، هندسه و کنترل کیفیت بیوتی، ریاضیات تایم‌لاین و تاریخچه‌ی ادیتور، زیرنویس‌ساز و پارس خروجی LLM، سیاست ادغام همگام‌سازی، محاسبه‌ی Entitlement و اعتبار، ایمپورترهای اسکریپت.
- اجرای همه‌ی تست‌ها: `./gradlew testDebugUnitTest`

---

## محدودیت‌های شناخته‌شده

- قابلیت‌های وابسته به سخت‌افزار (ضبط، GL، بیوتی، Bluetooth، مانیتورینگ) روی دستگاه‌های واقعی نیازمند تست میدانی گسترده هستند.
- HDR ده‌بیتی (HLG) پشتیبانی نمی‌شود چون خط لوله‌ی GPU هشت‌بیتی است؛ فقط حالت HDR صحنه در صورت پشتیبانی دستگاه.
- ML Kit کانتور را فقط برای یک چهره (برجسته‌ترین) ارائه می‌دهد؛ افکت‌های چهره روی یک نفر اعمال می‌شوند.
- Transitionها از نوع «عبور» هستند (بدون هم‌پوشانی دو کلیپ / cross-dissolve).
- Import فایل PDF پشتیبانی نمی‌شود.
- اصلاح تماس چشمی (Eye Contact) نیازمند سرویس سمت سرور است.
- پرداخت در کافه‌بازار/مایکت نیازمند افزودن SDK آن‌هاست.

</div>

---

## English summary

**RavanGo** is a production-oriented Android app for creating talking-head and social videos end to end:
**teleprompter → pro camera → real-time beauty & makeup → audio → multi-track editor → subtitles & AI → export**.
Persian is the primary language (full RTL); English is fully supported.

- **Stack:** Kotlin 2.2, Jetpack Compose (custom pastel/glass design system), Hilt, Room, DataStore, Camera2 + EGL/GLES + MediaCodec, ML Kit face contours, Media3 1.8 (Transformer/Effect/CompositionPlayer), Anthropic Java SDK (Claude via a server-side gateway), Supabase (auth, RLS sync, storage), Google Play Billing 8.
- **Modules:** `core:*` foundations, `engine:*` (render, camera, audio, beauty, teleprompter, editor, ai), `platform:*` (auth, cloud, billing), `feature:*` screens. See [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
- **Build:** `./gradlew :app:assembleDebug` / `:app:assembleRelease`; tests: `./gradlew testDebugUnitTest`.
- **Downloads:** GitHub Releases → `latest-build` (built by [CI](.github/workflows/android.yml) on every push).
- **Configuration:** `secrets.properties` or `RAVANGO_*` env vars; everything works offline, and features that need a service explain exactly which one. Backend setup: [`backend/README.md`](backend/README.md), AI gateway: [`backend/README-ai.md`](backend/README-ai.md).
- **Monetization:** Free / Pro (monthly, yearly with trial) / Lifetime + AI credit packs, no ads — [`docs/MONETIZATION.md`](docs/MONETIZATION.md).
- **Fonts & licenses:** brand font *Ravagh* (commercial, fontiran.com licence #136710, stored encrypted; decrypted in CI via `RAVAGH_FONT_PASSPHRASE`), Vazirmatn/Sahel/Samim (OFL 1.1), third-party libraries in the in-app *Licenses* screen and [`docs/THIRD_PARTY_LICENSES.md`](docs/THIRD_PARTY_LICENSES.md).

© RavanGo. All rights reserved.
