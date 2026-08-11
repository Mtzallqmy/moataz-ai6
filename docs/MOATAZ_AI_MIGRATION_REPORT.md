# تقرير تحويل RikkaHub Agent إلى Moataz AI v0.1

## خط الأساس الذي تم فحصه

المصدر المرفق يطابق خط RikkaHub Agent المبني على RikkaHub، وبنيته متعددة الوحدات. الوحدات المسجلة في `settings.gradle.kts` هي:

`app`, `highlight`, `ai`, `local-llm`, `llama-cpp`, `search`, `speech`, `common`, `document`, `web`, `material3`, `workspace`.

كما توجد `web-ui` لبناء واجهة الويب، و`build-logic` لمنطق Gradle المشترك.

القيم الأصلية المهمة قبل التخصيص:

- اسم المنتج: RikkaHub Agent / RikkaHub حسب السطح.
- `applicationId`: `excp.rikkahub`.
- Android namespace للتطبيق: `me.rerere.rikkahub`.
- `minSdk`: 26.
- `targetSdk`: 37.
- `compileSdk`: 37.
- Java/JVM: 17.
- Gradle Wrapper: 9.5.0.
- Android Gradle Plugin: 9.3.1.
- Kotlin: 2.4.10.
- الترخيص: GNU AGPL-3.0؛ لم يتم حذف `LICENSE` أو نسب المشروع الأصلي.

## القيم النهائية لـMoataz AI

- الاسم الظاهر: `Moataz AI`.
- root project: `moataz-ai`.
- `applicationId`: `ai.moataz`.
- Debug application id: `ai.moataz.debug` بسبب `applicationIdSuffix = ".debug"` الموجود أصلًا.
- namespace الداخلي: `me.rerere.rikkahub` (لم يُنفذ refactor شامل).
- `versionName`: `0.1.0` افتراضيًا.
- `versionCode`: `1` محليًا، ومع tags يستخدم Release workflow تحويل SemVer: `major*1,000,000 + minor*1,000 + patch`؛ مثل `v0.1.0 = 1000`.

## Deep Links وOAuth وExternal Automation

الـscheme الداخلي أصبح `moatazai://` ومتسقًا بين Manifest والكود:

- `moatazai://shortcut`
- `moatazai://codex/oauth`
- `moatazai://gemini/oauth`
- `moatazai://mcp-oauth-callback`

Codex وGemini يستخدمان callback loopback محليًا (`http://localhost:<port>/...`) أثناء OAuth ثم تعود صفحة callback إلى deep link الداخلي؛ تغيير اسم المنتج لا يغيّر عقد loopback نفسه.

MCP مختلف لأنه يرسل `moatazai://mcp-oauth-callback` إلى authorization server كـredirect URI. أي MCP/OAuth registration يفرض redirect allow-list ثابتة يحتاج إضافة URI الجديد خارجيًا وإعادة التفويض.

Intent Actions العامة أصبحت:

- `ai.moataz.RUN_TASK`
- `ai.moataz.RUN_CHAT`
- `ai.moataz.workflow.GEOFENCE_TRANSITION`

أي Tasker/MacroDroid/ADB automation قديم يعتمد `me.rerere.rikkahub.*` يجب تحديثه يدويًا. هذا تغيير متعمد لتحقيق استقلال التطبيق.

## Branding والهوية

تم استبدال الهوية المرئية إلى Moataz AI دون إعادة تسمية classes/packages الداخلية. تشمل التغييرات:

- Launcher label و`app_name` في جميع اللغات الموجودة.
- Launcher icon، Adaptive foreground/background، Monochrome icon.
- Splash icon/background.
- Notification small icon.
- About page، مع العبارة: «تم تطوير وتعديل Moataz AI بواسطة معتز العلقمي» ونسب RikkaHub/RikkaHub Agent والترخيص.
- Web UI sidebar ووثائق HTML.
- رسائل Telegram/Doctor/OAuth/permission recovery التي كانت تعرض اسم RikkaHub للمستخدم.
- شاشة المحادثة الفارغة تعرض: «أهلًا بك، معك Moataz AI. كيف أستطيع مساعدتك اليوم؟» فقط عندما تكون المحادثة بلا رسائل.
- مؤشر التوليد يستخدم علامة Moataz AI ونص «Moataz AI يفكر…» بدل مؤشر الأرنب القديم.
- avatar المساعد الافتراضي يستخدم علامة Moataz AI؛ assistants ذات هوية/اسم مخصص تبقى كما هي.

مسارات التخزين القديمة تحت `.../RikkaHub/` بقيت مقبولة داخل allow-list للحفاظ على التوافق وعدم كسر file protections، بينما اللقطات/الصور الجديدة تستخدم `Pictures/MoatazAI/...`.

## العربية وRTL

- العربية باقية لغة مدعومة مع `android:supportsRtl="true"`.
- أضيفت ترجمة عربية مصقولة للهوية، الـProvider presets، capabilities، Key Pool، حالات الاختبار، ورسائل الترحيب/التفكير.
- المصطلحات التقنية مثل API/MCP/SSH/OAuth/Token/Model تبقى بالإنجليزية عندما يكون ذلك أوضح.
- لم تُحذف الإنجليزية أو اللغات الأخرى.

## OpenAI-compatible core

بدل إنشاء networking implementation منفصل لكل خدمة، NVIDIA/Hugging Face/Custom تستخدم نفس `ProviderSetting.OpenAI` و`OpenAIProvider` وChat Completions transport.

Presets:

- NVIDIA NIM: `https://integrate.api.nvidia.com/v1` (قابل للتعديل، وkeyless مسموح للمضيفات الذاتية).
- Hugging Face: `https://router.huggingface.co/v1` (Model ID يُرسل كاملًا، بما في ذلك suffixes).
- OpenAI Compatible: Base URL/Key/Model/Headers يحددها المستخدم.

العقد: Base URL هو API root. النطاق بلا path يطبّع إلى `/v1`، ولا يُضاف `/v1` إذا كان path موجودًا. إذا لصق المستخدم endpoint كاملًا مثل `/v1/chat/completions` يُرجع إلى الجذر قبل تركيب المسار، وتُزال query/fragment من Base URL.

Chat Completions هو compatibility baseline. Responses API capability مستقلة ومطفأة افتراضيًا في NVIDIA/Hugging Face/Custom presets.

Capability overrides تشمل Streaming، Tool Calling، Vision، Reasoning، JSON Mode، Structured Output، Audio Input، Embeddings، Responses API، Image Generation. طبقة Chat Completions تفحص Tool/Vision/Reasoning قبل إرسالها.

`GET /models` اختياري؛ فشل discovery لا يمنع الإدخال اليدوي للنموذج.

## Multi API Keys

RikkaHub Agent كان يدعم عدة مفاتيح بصورة ضمنية عبر `KeyRoulette` داخل نص `apiKey` المفصول بمسافات/فواصل. لم يتم إنشاء نظام موازٍ أو حذف هذا التوافق.

أضيف `ApiKeyEntry` structured pool إلى OpenAI-compatible providers مع:

- `id`
- `label`
- `value`
- `enabled`
- `priority`
- `createdAt`

ويبقى `apiKey` القديم موجودًا ومزامنًا مع المفتاح الأساسي. عند قراءة إعداد قديم، `migrateLegacyApiKeyPool()` يحوله lazy/non-destructively إلى عناصر منظمة؛ DataStore/backup القديم يظل قابلًا للقراءة.

الاستراتيجيات:

- Primary Only.
- Failover.
- Round Robin باستخدام `AtomicInteger` thread-safe.
- Manual.

سياسة failover:

- 401/403: يمكن الانتقال للمفتاح التالي.
- 429: لا ينتقل افتراضيًا؛ يحتاج opt-in صريح.
- 5xx: يمكن الانتقال.
- timeout/network IO: يمكن الانتقال بصورة محدودة.
- 400 و404: لا تبديل افتراضيًا.
- الحد الأقصى للمفاتيح التي تُجرّب في الطلب الواحد: 3.

Streaming يثبت المفتاح طوال المحاولة. إذا وصل output معتبر فلا يُعاد الطلب بمفتاح آخر لتجنب تكرار الرد. Tool calling/conversation state لا يعتمد على اختيار المفتاح.

Custom `Authorization` header يتقدم على Bearer الذي يولده التطبيق، ولذلك تعرض الواجهة تحذيرًا وتعطل اختبار المفتاح الفردي عندما تكون هذه الترويسة موجودة.

مرحلة ثانية اختيارية: ربط Model محدد بـpreferred key لم يُفرض في v0.1 لأنه يحتاج توسيع Model schema وعلاقات حذف/restore أكثر من المطلوب للاستقرار.

## التخزين والأمان

- Provider settings (ومنها API keys) مخزنة في DataStore JSON كما في المشروع الأصلي، وليست Room entity؛ لذلك لا توجد Room migration لهذه الإضافة.
- مسار Backup/Restore يحفظ Settings كاملة؛ decoder الجديد يقبل الصيغة القديمة والجديدة.
- DataStore الحالي غير مشفر بـAndroid Keystore. لم يتم إضعافه أو نقله إلى cloud. تشفير credentials يحتاج migration مستقلة مدروسة كي لا يكسر backups والمستخدمين الحاليين.
- لم تُعطل Tool approvals أو HARDLINE أو trusted package checks أو Telegram whitelist أو MCP/Browser/SSH/file protections.
- لم تُضف Firebase أو Supabase أو backend خارجي.
- scan المصدر لا يحتوي مفاتيح إنتاج حقيقية؛ نتائج regex الوحيدة كانت fixtures داخل اختبارات SecretRedactor/SensitiveContentDetector.

## GitHub Actions

- `.github/workflows/android-build.yml`: `push`, `pull_request`, `workflow_dispatch`.
- `.github/workflows/android-release.yml`: tags `v*.*.*`, release published، و`workflow_dispatch`.

كلاهما يستخدم Wrapper `./gradlew`، JDK 17، Android SDK، Gradle cache، Bun، pnpm و`pnpm install --frozen-lockfile`.

Build workflow يشغل Web UI typecheck و`:ai:testDebugUnitTest` و`:app:testDebugUnitTest` ثم Debug APK وRelease APK وAAB ويرفعها Artifacts.

Release workflow يبني APK/AAB ثم يرفقهما بـGitHub Release. لا يوجد نشر Google Play.

Secrets الاختيارية للتوقيع:

- `MOATAZ_KEYSTORE_BASE64`
- `MOATAZ_KEYSTORE_PASSWORD`
- `MOATAZ_KEY_ALIAS`
- `MOATAZ_KEY_PASSWORD`

يُفك keystore إلى `.ci/` مؤقتًا، وتكتب خصائص `storeFile/storePassword/keyAlias/keyPassword` إلى `local.properties` أثناء CI، ثم تحذف الملفات في خطوة `always()`.

## التحقق

تحقق ساكن تم بنجاح من:

- parsing لجميع XML resources.
- سلامة Android resource identifiers الجديدة.
- عدم وجود missing base string resources جديدة.
- `git diff --check` دون whitespace errors.
- secret-like source scan (لا أسرار إنتاجية).
- اتساق Deep Links وIntent Actions بين Manifest والكود والاختبارات.

البناء المحلي لا يستطيع بدء Gradle لأن بيئة التنفيذ تحجب `services.gradle.org` ولا يوجد Android SDK مثبت. الخطأ يحدث قبل configuration/compilation (`UnknownHostException`)؛ لذلك لا يُعد فشلًا في كود المشروع، لكنه يعني أن نجاح APK/AAB يجب إثباته عبر GitHub Actions بعد رفع المستودع. لا يجوز وصف build بأنه ناجح قبل نتيجة CI.

## إعداد خارجي مطلوب قبل النشر

- إضافة Signing Secrets الأربعة إذا أردت Release موقّعًا.
- إعادة تسجيل/تفويض MCP OAuth integrations التي تثبت redirect URI القديم.
- تحديث Tasker/MacroDroid/ADB automations القديمة إلى Intent Actions الجديدة.
- مراجعة GitHub repository Actions permissions للسماح لـRelease workflow بكتابة releases (`contents: write`).
- تجربة OAuth الحقيقي وTelegram/SSH/Browser على جهاز Android فعلي قبل نشر v0.1 للمستخدمين.
