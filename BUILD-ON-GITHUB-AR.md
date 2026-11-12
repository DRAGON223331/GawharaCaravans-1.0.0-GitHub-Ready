# استخراج ملف JAR من GitHub

1. أنشئ Repository جديدًا على GitHub.
2. ارفع محتويات هذا المجلد إلى جذر الـ Repository، بما فيها مجلد `.github` وملف `pom.xml` ومجلد `src`.
3. افتح تبويب **Actions**.
4. اختر **Build GawharaCaravans** ثم اضغط **Run workflow**، أو اعمل Push إلى فرع `main`.
5. بعد نجاح المهمة، افتح صفحة التشغيل وانزل إلى **Artifacts**.
6. نزّل `GawharaCaravans-1.0.1`، وفك ضغطه لتحصل على `GawharaCaravans-1.0.1.jar`.

المشروع يستخدم JDK 25 وPaper API 26.3 build 170-beta وVaultAPI 1.7.
