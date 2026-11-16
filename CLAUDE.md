# Sofra: çalışma notları (her oturumda okunur)

Kullanıcıyla **Türkçe** konuş. Geliştirme dalı: `claude/gracious-davinci-m3v8sk`; yalnızca oraya push et, istenmeden
PR açma. Tanıtım videosu commit'i (yerel `promo-video-v2` dalı) **push edilmez**; `main` üzerinde çalışılmaz.
Commit mesajları kullanıcının istediği imza satırlarıyla biter; depoya model adı yazılmaz.

## Durum (15 Kasım 2026)

Sprint 1–4 bitti; Sprint 5'in (Sofra Cloud, ücretli ürün) kodu bitti: kayıt ve deneme, planlar ve sınırlar,
Paddle aboneliği, yalnızca okuma modu, işletme başına iyzico, yazıcı köprüsü, mağaza kabukları, kapalı lisans.
Ayrıntı: `docs/ROADMAP.md`, `docs/SOFRA_CLOUD_PLAN.md`, `docs/CLOUD.md`, `docs/PRINT_BRIDGE.md`.

## Kullanıcının yapacakları (kodla çözülemez)

- [ ] GitHub deposunu gizli yapmak; demoyu GitHub Pages'ten Vercel/Cloudflare'e taşımak (Claude hazırlar).
- [ ] Paddle hesabı açmak, Türkiye'den satıcı kabulünü teyit etmek; sandbox'ta 6 fiyat ve webhook tanımlamak.
- [ ] Alan adı ve bulut sunucusu (`sofra.app` belgelerde yer tutucu).
- [ ] Sunucuda `TOTP_ENCRYPTION_KEY` ayarlayıp yedeklemek (iyzico anahtarları bununla şifrelenir).
- [ ] iyzico üye işyeri ve e-Arşiv entegratörü sözleşmeleri.
- [ ] Google Play ($25) ve Apple Developer ($99/yıl) hesapları; iOS derlemesi için Mac.
- [ ] DE/FR/RU/AR metinlerini ana dili bu diller olan birine okutmak.

## Claude'un yapacakları (sırayla önerilen)

1. [ ] e-Arşiv / ÖKC ve paket servis platformları için işletme başına ayar ekranı (bulutta şu an kapalı).
2. [x] Yasal belge listesini şubeye göre süzmek (16 Kasım 2026: belge adisyonunun şubesine ait; V31).
3. [ ] Yeni sipariş push bildirimi (App Store başvurusundan önce gerekli).
4. [ ] Zincir planı vaatleri: merkezi menü, şube bazında fiyat, şubeler arası rapor (ya yap ya plan metninden çıkar).
5. [ ] PostgreSQL satır güvenliği (RLS), ikinci yalıtım duvarı.
6. [ ] Paddle'ı sandbox'ta kullanıcıyla birlikte doğrulamak (bu ortamdan Paddle'a erişim yok).
7. [ ] İsteğe bağlı: iyzico aboneliği (TL), sadakat, saatlik fiyat, müşteri ekranı.

## Tasarım: kullanıcının "UI UX Tasarım Stüdyosu" (ikinci beyin)

Kaynak: kullanıcının notu "UI UX Tasarım Stüdyosu" (14 Kasım 2026). Bağlantılı notlar (43 kalıp, 24 tema × 12 hareket ×
18 anlatı, hareket token'ları, Sofra rehberi) bu ortamda **yok**; gerekirse kullanıcıdan istenir.

- İlke: gerçek bir kullanıcı sorununu yaratıcı, estetik, hızlı ve rahat çözmek. **"Wow" etkisi, kullanılabilirlik ve
  güvenlik kapılarından sonra** değerlendirilir.
- Üç aday yaratıcı yön (kullanıcı henüz seçmedi, marka/font/renk dayatmaz):
  1. **Akışın görünür hâli**: dağınık girdilerden okunur, kontrol edilebilir sonuca geçiş.
  2. **Hızın sakin hissi**: açık tipografi, güçlü eylem hiyerarşisi, küçük doğrulanmış durum tepkileri (sık
     kullanılan iş ekranları için).
  3. **Tek fikir, tek imza anı**: bir obje, tipografik motif ya da eşleşmeli kesmeyle yaratıcı anlatım.
- Çalışma şekli: her ekran için iki-üç aday; nedenleri, mobil karşılığı, hareket azaltma ve maliyetiyle sunulur;
  **kullanıcı seçer ve onaylar**, sonra uygulanır. Her tasarım 6 dilde (Arapça sağdan sola), açık/koyu temada,
  telefon/tablet/masaüstünde, `prefers-reduced-motion` ile ve dokunmatikte çalışmalı.
- Kullanıcının katalogları (15 Kasım 2026'da yüklendi, yalnızca isim/adres listeleri, içerikleri incelenmemiş):
  Impeccable (Apache-2.0; tasarım hatası denetim kuralları: geri sekmeli easing, sürekli yanıp sönen nokta, arka plan
  haresi, iç içe kart vb. **kalite kapısı olarak kullanılır**), ObsidianUI (MIT; flip-text, skeleton, status-bars,
  sonner, drawer gibi bileşen adları), v0 şablon adresleri (receipt, kitchen menu, order detail), iter0 ve
  designbookmark (site şablonları ve araç listeleri; lisansı belirsiz olanlardan kod alınmaz).
- Hareket adayları sayfası (A1–A4, B1–B2, C1–C2): https://claude.ai/artifact/57HPtoGUCVnQzq38iGbGoB. Kullanıcı
  15 Kasım 2026'da **sekizini de onayladı; hepsi uygulandı** (ayrıntı: `DESIGN.md` → Motion → Patterns in use).
  Sonraki tasarım işleri yine aynı yoldan: aday sayfası → kullanıcı seçer → uygulanır.
- 2. tur adaylar (D1 işletme rengi, D2 uzaktan okunur mutfak, D3 kategori şeridi, E1 geri al, E2 kaydırarak hazır,
  E3 hızlı arama, F1 masa bekleme halkası, F2 günün kartı): https://claude.ai/artifact/MAXCSVmBM4LJwjST84UgWa.
  Kullanıcı **sekizini de onayladı** (16 Kasım 2026); **hepsi uygulandı** (ayrıntı: `DESIGN.md` → Round two).
  Eksik kalanlar: E1 kalem iptali ve masa kapatmada yok; F1 eşikleri işletme ayarında değil cihaz başına seçiliyor.
- Sofra'nın kendi tasarım dili: `DESIGN.md` (lokanta sıcaklığı, domates kırmızısı, safran; Fraunces + Inter;
  easing `cubic-bezier(.2,.8,.2,1)`; yalnızca transform/opacity animasyonu).
