# Sofra tanıtım videosu: plan

30 saniyelik, sessiz izlenebilen, animasyonlu bir tanıtım videosu. LinkedIn ve Instagram için.

| | |
|---|---|
| **Dosyalar** | [`video/out/sofra-tr-9x16.mp4`](video/out/sofra-tr-9x16.mp4) (Reels, Hikâye, LinkedIn dikey) · [`video/out/sofra-tr-4x5.mp4`](video/out/sofra-tr-4x5.mp4) (LinkedIn ve Instagram akışı) |
| **Kapak** | [`video/out/sofra-cover-9x16.png`](video/out/sofra-cover-9x16.png) · [`video/out/sofra-cover-4x5.png`](video/out/sofra-cover-4x5.png) |
| **Süre** | 30,2 sn, 30 fps |
| **Dil** | Türkçe (yazılar videonun içinde; ses gerekmez) |
| **Kaynak** | [`video/`](video): tek bir HTML sahnesi, kare kare işlenir. Yeniden üretim: [`video/README.md`](video/README.md) |

## Amaç ve mesaj

**Tek cümle:** *Misafir sipariş verir, garson servis eder, mutfak hiç beklemez.*

**Çözdüğü sorun:** Restoranda zamanın çoğu beklemekle geçer: sipariş vermek için garsonu, hesabı istemek için garsonu,
ödemek için POS cihazını. Sofra bu üç beklemeyi masadaki QR kodla kaldırır; garsona sipariş almak yerine servis
etmek kalır.

**Hedef kitle**

- **LinkedIn:** yazılım ekipleri ve işe alımcılar (projenin kapsamı ve kalitesi), restoran işletmecileri ve
  restoran teknolojisiyle ilgilenenler (ürünün çözdüğü iş).
- **Instagram:** restoran sahipleri, kafe işletmecileri, yeme-içme sektöründen takipçiler.

**İzleyiciden istenen:** GitHub adresine gitmesi (kapanışta adres ve okutulabilir QR kod var).

## Senaryo

Kurgu, uygulamanın kendi "sipariş takip çubuğunu" bölüm göstergesi olarak kullanır: Sipariş → Mutfak → Servis → Ödeme.
Telefon ve tablet ekranlarının hepsi uygulamanın gerçek ekran görüntüleridir (bir masanın akşamı: masa 4, sipariş
verilir, pişer, servis edilir, ödenir).

| Süre | Sahne | Ekranda | Yazı |
|---|---|---|---|
| 0,0–3,5 | **Sorun** (koyu) | Üç satır, yanlarında işleyen süre sayaçları; sonra üstleri çizilir | "Sipariş vermek için bekle. Hesabı istemek için bekle. Ödemek için bekle." → "Ya kimse beklemeseydi?" |
| 3,5–6,0 | **Logo** | Domates kırmızısı daire ekranı kaplar, krem zemine açılır; logo, buharı çizilerek gelir | "Sofra. Misafir sipariş verir. Garson servis eder." |
| 6,0–10,9 | **1 · Sipariş** | Masadaki QR kartı taranır; telefon gelir: menü, alerjenler, ürün ekleme, sepet ve "yanına iyi gider" önerileri | "QR'ı okut, siparişini ver. Uygulama indirmek yok." |
| 10,9–15,4 | **2 · Mutfak** | Mutfak ekranına yeni fiş düşer; misafirin telefonundaki takip kartı: Mutfağa ulaştı → Hazırlanıyor → Hazır | "Sipariş anında mutfakta. Misafir de yemeğini canlı izler." |
| 15,4–18,7 | **3 · Servis** | Garsonun servis ekranı: hazır tabak, tek dokunuş, "Masa 4: servis edildi" | "Garson sipariş almaz, servis eder." |
| 18,7–23,5 | **4 · Ödeme** | Masadan ödeme: Hepsi → Eşit böl → Yediklerim; "Teşekkürler! ₺693,00 alındı." | "Hesap masadan ödenir. Bölüş, yalnızca yediğini öde, bahşiş bırak, faturanı al." |
| 23,5–26,7 | **İşletme** | Yönetici paneli ve altı özellik etiketi | "İşletme de tek ekranda": stok ve reçete maliyeti, çevrimdışı çalışma, Yemeksepeti · Getir · Trendyol, 6 dil, ÖKC fişi · e-Arşiv, iki adımlı giriş |
| 26,7–30,2 | **Kapanış** (koyu) | Logo, slogan, teknoloji satırı, QR kod ve GitHub adresi | "Misafir sipariş verir, garson servis eder, mutfak hiç beklemez." |

## Tasarım kararları

- **Sofra'nın kendi dili:** krem zemin, domates kırmızısı, safran ve espresso; başlıklarda Fraunces, metinde Inter
  (bkz. [DESIGN.md](../DESIGN.md)). Sorun ve kapanış koyu, çözüm aydınlık: karşıtlık hikâyeyi taşır.
- **Sessiz izlenir:** sosyal medyada videoların çoğu sessiz açılır; bütün anlatım ekrandaki yazıyla yapılır.
- **İlk 3 saniye kanca:** tanıdık bir sıkıntı (beklemek) ve bir soru. Ürün adı sorunun cevabı olarak gelir.
- **Güvenli alan:** önemli her şey karenin y=250…1480 aralığındadır; Reels arayüzü (üstte ~250, altta ~440 piksel)
  hiçbir yazıyı kapatmaz. 4:5 sürümü aynı karelerin y=190…1540 kırpımıdır, ayrı bir kurgu değildir.
- **Gerçek ekranlar:** arayüz çizim değil, çalışan uygulamadan alınmış görüntüdür; rakamlar birbirini tutar
  (1 İskender + 1 Ayran = ₺630, %10 bahşişle ₺693).
- **Hareket:** girişler aşağıdan, yumuşak yavaşlamayla; ekran değişimleri kısa; dokunuşlar parmak iziyle gösterilir.

## Teknik özellikler

| | 9:16 | 4:5 |
|---|---|---|
| Çözünürlük | 1080×1920 | 1080×1350 |
| Kullanım | Instagram Reels ve Hikâye, LinkedIn dikey video | LinkedIn akışı, Instagram akış gönderisi |
| Kodek | H.264 (High), yuv420p, BT.709, 30 fps, MP4 (faststart) | aynı |
| Ses | sessiz AAC izi (bazı yükleyiciler ses izi olmayan dosyayı reddeder) | aynı |
| Boyut | ~8 MB | ~6 MB |

Ölçüler üçüncü taraf rehberlerden derlendi (platformların resmî belgeleri değil, kendi aralarında da küçük farklar
var): Reels için 1080×1920, 30 fps ve ortadaki ~1080×1420 güvenli alan; LinkedIn akışı için 4:5 ya da 9:16, 30 saniye
civarı süre ve videoya gömülü yazı.

## Paylaşım metinleri

**LinkedIn**

> Restoranda zamanın çoğu beklemekle geçiyor: sipariş vermek için, hesabı istemek için, ödemek için.
>
> Sofra'yı bu üç beklemeyi kaldırmak için geliştirdim. Misafir masadaki QR kodu okutup siparişini veriyor, yemeğinin
> durumunu canlı izliyor ve hesabını masadan ödüyor (bölüşerek ya da yalnızca yediğini). Sipariş anında mutfak
> ekranına düşüyor. Garsona sipariş almak değil, servis etmek kalıyor.
>
> Arkasında: Spring Boot 4, Java 21, PostgreSQL, Server-Sent Events ile canlı ekranlar, çevrimdışı çalışan kasa,
> stok ve reçete maliyeti, Yemeksepeti/Getir/Trendyol sipariş entegrasyonu, ÖKC fişi ve e-Arşiv için hazır altyapı,
> altı dil. 250 backend testi ve 65 tarayıcı testiyle.
>
> Açık kaynak. Kod ve kurulum: github.com/UmutVarol/Restaurant-Order-Processing
>
> #yazılım #java #springboot #restoran #açıkkaynak #pos

**Instagram**

> Sipariş için bekle, hesap için bekle, ödemek için bekle… Ya kimse beklemeseydi? 🍽️
>
> Sofra: misafir QR'dan sipariş verir, yemeğini canlı izler, hesabı masadan öder. Garson sipariş almaz, servis eder.
>
> Açık kaynak restoran işletim sistemi. Bağlantı profilde.
>
> #restoran #kafe #restoranyönetimi #qrmenü #pos #yazılım #girişim #springboot #java #açıkkaynak

## Yayın kontrol listesi

- [ ] LinkedIn akışı için `sofra-tr-4x5.mp4`; Reels ve Hikâye için `sofra-tr-9x16.mp4`.
- [ ] Kapak olarak ilgili `sofra-cover-*.png` (videonun ilk karesi koyu; kapakta logo daha iyi durur).
- [ ] LinkedIn'de bağlantıyı gönderi metnine ya da ilk yoruma ekleyin; Instagram'da bağlantı profilde olmalı.
- [ ] Video sessizdir. İsterseniz platformun kendi müzik kitaplığından sakin, enstrümantal bir parça ekleyin
      (telif açısından en güvenli yol budur).
- [ ] GitHub deposunun herkese açık olduğundan emin olun; kapanıştaki adres ve QR kod oraya gider.

## Sonraki adımlar (istenirse)

- **İngilizce sürüm:** metinler `video.html` içinde hazır (`?lang=en`); İngilizce ekran görüntüleri için
  `capture.js … en` çalıştırılıp yeniden işlenmesi yeterli. Henüz işlenmedi ve gözden geçirilmedi.
- **15 saniyelik kısa kesim:** yalnızca sorun + dört bölüm başlığı + kapanış.
- **Rol bazlı kısa videolar:** yalnızca misafir akışı, yalnızca mutfak, yalnızca yönetici paneli.
