# GawharaCaravans 1.1.0 — GC Shop Mode

تم الحفاظ على الشراء والبيع بالدولار عبر Vault. أُضيف وضع عملة مستقل للقافلة: افتح متجر التاجر واضغط زر تبديل العملة أسفل القائمة بين Vault Cash وGawhara Coins. في وضع GC، Left/Right Click يشتري 1 أو 8 من المنتج بالـ GC. Shift+Left/Right يظل بيعًا بالدولار عبر Vault.

لكل منتج أضف `coins-buy-price` في `shop.products` داخل config.yml؛ المنتجات التي قيمتها 0 لا يمكن شراؤها بالـ GC. الأسعار تتأثر بنفس معامل الأسعار الديناميكية.

يحتاج Vault للاقتصاد النقدي، ويستخدم GawharaCoins عبر `softdepend`. ثبّت GawharaCoins أولًا.

البناء: Java 25 + Paper 26.3 build 170-beta.
