# GawharaCaravans 1.0.1

بلجن تاجر متنقل لسيرفر GawharaSMP، مع متجر GUI ومخزون وأسعار متغيرة وربط Vault Economy.

## المتطلبات

- Paper 26.3 build 170-beta
- Java 25
- Vault
- بلجن اقتصاد يدعم Vault مثل EssentialsX Economy

## البناء من GitHub

1. ارفع محتويات المشروع إلى Repository جديد.
2. افتح Actions وشغّل **Build GawharaCaravans** أو ادفع commit إلى `main`.
3. بعد نجاح البناء، افتح Artifact باسم `GawharaCaravans-JAR` وحمّل JAR.

## التثبيت

ضع `GawharaCaravans-<version>.jar` في `plugins` مع Vault وبلجن الاقتصاد، ثم أعد تشغيل السيرفر. سيُنشأ `plugins/GawharaCaravans/config.yml` تلقائيًا.

## كيف يعمل

- تظهر قافلة تلقائيًا بعد التأخير الأولي، ثم بفواصل زمنية عشوائية.
- يتم اختيار عالم عشوائي من `settings.enabled-worlds`.
- الافتراضي: `world`, `world_nether`, `world_the_end`.
- `spawn` موجود في `blocked-worlds` وممنوع حتى لو أُضيف بالخطأ إلى القائمة المسموح بها.
- يتم اختيار مكان آمن على سطح العالم داخل دائرة نصف قطرها مضبوط في الكونفج، مع احترام World Border والابتعاد عن Spawn.
- تظهر Villager كتاجر ومعه Llamas، وعند الضغط عليه بالزر الأيمن تفتح واجهة المتجر.
- الأسعار والمخزون يتغيران عشوائيًا في كل زيارة.
- Left Click: شراء 1، Right Click: شراء 8، Shift+Left: بيع 1، Shift+Right: بيع 8.
- لا يتم بيع عناصر غير مطابقة لمادة المنتج، والبيع/الشراء يتم عبر Vault.

## الأوامر

| الأمر | الوظيفة |
|---|---|
| `/caravans` أو `/caravan` | معلومات وحالة القافلة |
| `/caravans locate` | عرض الإحداثيات وتوجيه البوصلة إذا كنت في نفس العالم |
| `/caravans spawn [world]` | إنشاء قافلة يدويًا |
| `/caravans despawn` | إزالة القافلة الحالية |
| `/caravans setcenter` | حفظ موقعك الحالي كمركز عشوائي لهذا العالم |
| `/caravans reload` | إعادة تحميل الإعدادات والجدولة |

## الصلاحيات

- `gawharacaravans.use` — افتراضيًا للجميع.
- `gawharacaravans.admin` — افتراضيًا للأوب، لأوامر الإدارة.

## الإعدادات المهمة

في `plugins/GawharaCaravans/config.yml`:

- `settings.enabled-worlds`: العوالم التي يُسمح بظهور القوافل فيها.
- `settings.blocked-worlds`: عوالم ممنوعة دائمًا؛ اترك `spawn` هنا.
- `settings.interval-minutes`: أقل وأكبر مدة بين القوافل.
- `settings.stay-minutes`: مدة بقاء القافلة.
- `settings.spawn-radius`: نطاق المكان العشوائي حول Spawn أو مركز `setcenter`.
- `settings.protect-spawn-radius`: مسافة الحماية من Spawn الأصلي.
- `shop.products`: المواد والأسعار والمخزون والخانات.

## ملاحظات

- القافلة الواحدة فقط تكون نشطة في الوقت نفسه.
- لا يتم تغيير بلوكات العالم عند ظهور القافلة؛ يتم إنشاء Villager وLlamas فقط.
- عند إغلاق البلجن، تزال الكيانات التابعة له. كما ينظف البلجن الكيانات القديمة ذات العلامة الخاصة به عند التشغيل.
- اختبره على نسخة احتياطية أولًا، خصوصًا مع بلجنات حماية المناطق التي قد تمنع تفاعل اللاعبين مع الـ Villager.
