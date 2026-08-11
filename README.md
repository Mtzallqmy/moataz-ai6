<div dir="rtl" align="right">

# Moataz AI

**وكيل ذكاء اصطناعي مفتوح المصدر لنظام Android بهوية عربية أولًا وتجربة Agent متكاملة.**

Moataz AI هو تخصيص وتطوير لمشروع Android قائم، وليس إعادة كتابة من الصفر. يحافظ على البنية والوظائف الأساسية وآليات الأمان، مع هوية مستقلة، `applicationId` مستقل، CI/CD على GitHub، ودعم موسّع لمزوّدي OpenAI-compatible مثل NVIDIA NIM وHugging Face.

> **تنبيه أمني:** التطبيق يوفّر أدوات حساسة يمكنها — بعد منح الصلاحيات والموافقات المطلوبة — التعامل مع الملفات، الشاشة، الإشعارات، SSH، Termux، الرسائل، الموقع، المتصفح وخدمات أخرى. لا تمنح صلاحية أو تفعّل أداة لا تحتاجها، ولا تستخدم نماذج أو MCP Servers أو مزوّدات لا تثق بها. آليات Tool approvals وHARDLINE وفحوص الصلاحيات والحزم الموثوقة والقوائم البيضاء لم تُعطّل في هذا التخصيص.

## أهم الميزات

- محادثة ووكلاء AI مع **Tool Calling** وSub-agents وسياقات متعددة.
- Workflows وأتمتة محلية ومهام مجدولة Schedules تعمل وفق المحفزات والشروط الموجودة في المشروع.
- Telegram Bot مع قائمة سماح وموافقات للأدوات الحساسة.
- Browser Agent داخل التطبيق، وأدوات Web Search وWeb Fetch.
- SSH لإدارة الخوادم البعيدة، مع الحفاظ على ضوابط الأمان الأصلية.
- Workspace/Termux لتشغيل أوامر وعمليات طويلة وإدارة ملفات مساحة العمل.
- MCP لربط خوادم Model Context Protocol مع مسار OAuth وموافقات الأدوات.
- Local LLM عبر الإمكانات المحلية الموجودة مثل LiteRT/llama.cpp/AICore حيث يدعم الجهاز.
- أدوات ملفات، وسائط، إشعارات، شاشة، نظام، ومزايا Android الأخرى حسب الصلاحيات التي يمنحها المستخدم.
- تخزين محلي قائم على الآليات الموجودة في المشروع مثل Room/SQLite وDataStore؛ **لا يضيف هذا الفرع Supabase أو Firebase أو Backend جديدًا**.

## مزودو الذكاء الاصطناعي

يحتفظ Moataz AI بالمزوّدات الأصلية مثل OpenAI وOpenRouter وGemini/Google وAnthropic/Codex/Grok والمزوّدات المحلية وغيرها، ويضيف presets رسمية فوق نفس طبقة OpenAI-compatible المشتركة:

- **NVIDIA NIM** — Base URL افتراضي: `https://integrate.api.nvidia.com/v1`، ويمكن تغييره إلى NIM مستضاف ذاتيًا مثل `http://192.168.1.10:8000/v1`. يمكن ترك API Key فارغًا عند استخدام endpoint خاص لا يتطلب مصادقة.
- **Hugging Face** — Base URL افتراضي: `https://router.huggingface.co/v1`. يُرسل Model ID كما كتبه المستخدم، بما في ذلك suffixes مثل `:fastest` و`:cheapest` و`:preferred`.
- **OpenAI Compatible** — مزوّد عام لأي API متوافق مع Chat Completions. يمكن إدخال Name وBase URL وAPI Key وModel ID وCustom Headers وإعدادات متقدمة للقدرات.

العقد المستخدم للـBase URL هو **جذر API** وليس `/chat/completions` نفسه. إذا أُدخل نطاق بلا مسار مثل `https://api.example.com` يطبّع التطبيق الجذر إلى `/v1`. إذا كان المسار موجودًا أصلًا، مثل `/v1` أو `/api/v1` أو `/compatible-mode/v1`، فلا يضيف `/v1` مرة ثانية.

جلب النماذج عبر `/models` اختياري. إذا لم يدعمه المزوّد أو فشل، يستطيع المستخدم إضافة Model ID يدويًا. Chat Completions هو baseline للتوافق، أما Responses API فيبقى capability مستقلة ولا يُفترض توفرها في كل endpoint. كما يمكن ضبط Streaming وTool Calling وVision وReasoning وJSON Mode وStructured Output وAudio وEmbeddings وResponses API وImage Generation من Advanced Settings؛ وتبقى قدرات النموذج نفسه (مثل Tool/Vision) عاملًا إضافيًا قبل إرسال الميزة.

### مفاتيح API متعددة

مزودات OpenAI-compatible — ومنها OpenAI وNVIDIA NIM وHugging Face وOpenRouter والمزود العام — تستخدم Key Pool واحدة داخل نفس Provider بدل تكرار المزود. الصيغة القديمة التي كانت تسمح بكتابة أكثر من مفتاح داخل `apiKey` مفصولة بمسافات أو فواصل تُقرأ تلقائيًا وتتحول بصورة غير مدمرة إلى عناصر منظمة. يبقى `apiKey` القديم متزامنًا مع المفتاح الأساسي للتوافق مع النسخ والنسخ الاحتياطية الأقدم.

الاستراتيجيات المتاحة هي **Primary Only** و**Failover** و**Round Robin** و**Manual**. التبديل عند HTTP 429 معطّل افتراضيًا ويجب تفعيله صراحةً، بينما أخطاء request غير الصالح 400 وModel Not Found 404 لا تغيّر المفتاح. عدد محاولات المفاتيح محدود، وStreaming لا يبدّل المفتاح بعد وصول محتوى معتبر حتى لا يتكرر الرد. يمكن تمكين/تعطيل كل مفتاح، تعيين الأساسي، إخفاؤه/إظهاره مؤقتًا، واختباره منفردًا من صفحة المزود.

> **ملاحظة أمان:** تخزين مفاتيح المزودات يستمر باستخدام DataStore الحالي للمشروع؛ لم يكن التخزين الأصلي مشفرًا بـAndroid Keystore، ولم يغيّر هذا التخصيص مستوى الحماية أو ينقل المفاتيح إلى Cloud. تشفير الاعتمادات باستخدام Keystore/Encrypted storage مناسب كتحسين أمني مستقل بعد التحقق من مسار migration والنسخ الاحتياطية.

## العربية وRTL

واجهة Android تتضمن العربية مع `supportsRtl=true`، وقد أصبحت الهوية والنصوص الأساسية باسم **Moataz AI**. بقيت الإنجليزية واللغات الأخرى كما هي. المصطلحات التقنية مثل API وMCP وSSH وOAuth وToken وModel تُترك بالإنجليزية عندما يكون ذلك أوضح، بينما تستمر مكوّنات عرض الكود والروابط باستخدام منطق الاتجاه الموجود في المشروع حتى لا تتأثر بـRTL.

## متطلبات البناء

- Android SDK مع `compileSdk/targetSdk 37` و`minSdk 26`.
- JDK 17.
- Gradle Wrapper المرفق (`Gradle 9.5.0`).
- Android Gradle Plugin `9.3.1` وKotlin `2.4.10` كما هي مثبتة في المشروع.
- Bun وpnpm لبناء `web-ui` قبل/أثناء بناء موديول `web`.
- CMake/NDK المطلوبان للوحدات الأصلية مثل `workspace` و`llama-cpp`.

### بناء محلي

```bash
# من جذر المشروع
bun --version
pnpm --version
./gradlew :ai:testDebugUnitTest :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease
./gradlew :app:bundleRelease
```

بدون إعداد signing سيُنتج Release غير موقّع. للتوقيع محليًا أنشئ `local.properties` غير المتتبع في Git بالقيم التالية، ولا ترفعها إلى المستودع:

```properties
storeFile=/absolute/path/to/moataz-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

## GitHub Actions

يوجد مساران داخل `.github/workflows/`:

- `android-build.yml`: يعمل على `push` و`pull_request` و`workflow_dispatch`. يثبت JDK/Android SDK/Bun/pnpm، يشغّل typecheck واختبارات JVM، ثم يبني Debug APK وRelease APK وRelease AAB ويرفعها كـArtifacts.
- `android-release.yml`: يعمل على tag بشكل `vX.Y.Z`، وعند نشر GitHub Release، ويمكن تشغيله يدويًا مع tag. يبني Release، يرفع Artifact، ثم ينشئ/يحدّث GitHub Release ويرفق APK/AAB.

### Secrets الخاصة بالتوقيع

أضف القيم التالية في **Repository Settings → Secrets and variables → Actions** عند الرغبة في Signed Release:

- `MOATAZ_KEYSTORE_BASE64`
- `MOATAZ_KEYSTORE_PASSWORD`
- `MOATAZ_KEY_ALIAS`
- `MOATAZ_KEY_PASSWORD`

يتم فك keystore إلى ملف مؤقت داخل runner وكتابة `local.properties` مؤقت، ثم حذف الاثنين في خطوة `always()`. عند غياب Secrets يستمر build كـunsigned؛ وفي Release automation تُسمى الملفات صراحةً `-unsigned` حتى لا يختلط الأمر على المستخدم. عند توفر signing تكون الأسماء مثل `moataz-ai-v0.1.0.apk` و`moataz-ai-v0.1.0.aab`.

## Versioning

النسخة الأساسية لهذا الفرع تبدأ من:

- `versionName = 0.1.0`
- `versionCode = 1`

يمكن تمرير `-PMOATAZ_VERSION_NAME` و`-PMOATAZ_VERSION_CODE` للبناء. Workflow الخاص بالـRelease يأخذ `versionName` من tag ويحوّل SemVer إلى `versionCode` ثابت ومتزايد بالصيغة `major*1,000,000 + minor*1,000 + patch`؛ مثل `v0.1.0 → 1000` و`v0.1.1 → 1001`. ولا يُعاد استخدام خط إصدارات المشروع السابق؛ لدى Moataz AI release lineage مستقل.

## تثبيت APK

حمّل APK من GitHub Actions Artifacts أو GitHub Releases. APK الموقّع هو المناسب للتثبيت والتحديث. ملفات `*-unsigned.apk` نواتج تحقق/بناء وليست بديلًا عن توقيع إنتاجي. عند تثبيت APK خارج Play Store قد تحتاج إلى السماح بالتثبيت من المصدر الذي تستخدمه، ثم راجع الصلاحيات واحدةً واحدة قبل تفعيل الأدوات الحساسة.

## Deep Links وOAuth والأتمتة الخارجية

الهوية العامة الجديدة تستخدم scheme:

`moatazai://`

وتشمل callbacks الداخلية لـCodex وGemini، واختصار التطبيق، وMCP OAuth callback. Codex/Gemini يستخدمان أيضًا callback محليًا أثناء OAuth قبل إعادة النتيجة إلى deep link الداخلي. تغيير MCP redirect URI عن أي تسجيل سابق يحتاج إعادة تفويض/تسجيل، وقد يحتاج إعدادًا خارجيًا إذا كان مزوّد MCP يفرض Redirect URI ثابتًا. التفاصيل الدقيقة لمسار الترحيل موجودة في `docs/INTEGRATIONS.md`.

كما أصبحت Intent Actions العامة:

- `ai.moataz.RUN_TASK`
- `ai.moataz.RUN_CHAT`
- `ai.moataz.workflow.GEOFENCE_TRANSITION`

أي إعدادات قديمة في Tasker/MacroDroid/ADB تعتمد القيم السابقة يجب تحديثها يدويًا.

## الشعار والأيقونات

تم إنشاء هوية أصلية لـ**Moataz AI** بعلامة M تقنية متدرجة، وتطبيقها على Launcher/Adaptive/Monochrome icons، Splash، صفحة About، الترحيب في المحادثة، avatar الافتراضي للمساعد وأيقونة الإشعارات. الأصول الأساسية هي:

- `app/src/main/res/drawable/moataz_ai_mark.xml`
- `app/src/main/res/drawable/moataz_ai_mark_monochrome.xml`
- `app/src/main/res/drawable/moataz_splash_background.xml`
- `app/src/main/res/drawable/small_icon.xml`
- `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- `app/src/main/res/values-v31/themes.xml`
- `docs/icon.svg`

تظل Adaptive Icon واضحة في Light/Dark لأن خلفية الأيقونة مستقلة، بينما واجهة التطبيق نفسها تواصل استخدام Material 3 وDynamic/Light/Dark themes الأصلية.

## التخزين والأسرار

مفاتيح OpenAI/OpenRouter/NVIDIA/Hugging Face/Telegram وبيانات SSH لا توضع في Source Code أو GitHub Actions. يستخدم مزوّدو LLM نفس آلية تخزين إعدادات المزوّدات الموجودة في المشروع. Custom Headers الحساسة لا تُطبع عمدًا في سجلات الطلبات، وAuthorization لا يُكرر إذا حدده المستخدم صراحةً في Custom Headers.

## الأصل والترخيص

Moataz AI **مبني على/مشتق من RikkaHub Agent**، والذي هو بدوره fork من **RikkaHub**. لم يُكتب المشروع بالكامل من الصفر، ولا يدّعي هذا الفرع ذلك. يُحافظ هذا المستودع على ملف `LICENSE` وترخيص **GNU AGPL-3.0** والتزامات copyleft والإتاحة المصدرية المنطبقة على العمل المشتق.

المشاريع الأصلية التي يجب نسب الفضل إليها تشمل:

- RikkaHub: `https://github.com/rikkahub/rikkahub`
- RikkaHub Agent: المشروع الذي بُني عليه هذا التخصيص مباشرةً.
- Termux وllama.cpp والمكتبات والمشاريع الأخرى المذكورة في الكود وتراخيص الطرف الثالث الخاصة بها.

هذا التخصيص غير تابع رسميًا لمشرفي RikkaHub أو RikkaHub Agent. راجع `LICENSE` قبل إعادة التوزيع أو تقديم التطبيق كخدمة شبكية، واحفظ إشعارات حقوق النشر والتراخيص المطلوبة.

</div>
