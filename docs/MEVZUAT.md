# Mevzuat: fiyatlar, fiyat listesi ve e-Adisyon

Son güncelleme: 24 Kasım 2026. Bu belge, Türkiye'de bir lokantanın fiyatları ve belgeleri için yasanın istediklerini
Sofra'nın nasıl karşıladığını anlatır. Kaynaklar ticari rehberler ve Bakanlık duyurularıdır; **oranlar ve
yorumlar bir mali müşavirle teyit edilmelidir** (`YAPILACAKLAR.md` › 3b).

## 1. KDV dahil fiyat ve ürün bazında KDV oranı

**Yasa:** Menüdeki fiyat, misafirin ödediği son fiyattır; "KDV hariç" ibaresi ihlaldir (Fiyat Etiketi
Yönetmeliği). Yeme-içme hizmetinde KDV %10'dur; alkollü içeceklere düşen kısım %20'dir.

**Sofra'da:**

- Her ürünün bir **KDV oranı** var (Menü › ürün › KDV oranı: %0, %1, %10, %20). Yeni ürün %10 ile başlar.
- **Menü fiyatı KDV dahildir.** Toplam = satırlar − indirim + teslimat ücreti. KDV, bu toplamın *içinde* oran oran
  hesaplanır: `KDV = brüt × oran / (100 + oran)`, kuruşa yarım yukarı yuvarlanır.
- **İndirim** (kupon veya yönetici indirimi) oranlara, her oranın tutarı ölçüsünde dağıtılır; kuruş artığı en
  çok yuvarlama kaybı olan orana verilir, böylece paylar toplamı tutar.
- **Teslimat ücreti** KDV dahildir ve yemeğin satışının parçası sayılarak %10'dan hesaplanır
  (`restaurant.pricing.delivery-vat-rate`, teyit edilecek).
- Kasa, sipariş ayrıntısı ve fiş toplamın altında **"İçindeki KDV (%10) / (%20)"** satırlarını gösterir.
  ÖKC/e-Arşiv'e giden istekte her satırın oranı (`vatRate`) ve oran bazında KDV listesi (`vat`) vardır.
- **Geçiş (V37):** Daha önce menüde KDV hariç fiyat tutuluyor ve %10 hesapta ekleniyordu. Göç, misafirin ödediği
  tutar değişmesin diye kayıtlı her fiyatı (ürün, seçenek, şube fiyatı) %10 artırarak KDV dahil hâle getirir.
  Göçten önce verilmiş siparişler eski kuralla saklı kalır (`taxIncluded = false`) ve değiştirilirse eski kuralla
  yeniden fiyatlanır.
- Yemek maliyeti yüzdesi (reçeteler) KDV hariç fiyattan hesaplanır: restoranın elinde kalan tutar.

## 2. Fiyat listesi: kapıda ve masalarda

**Yasa:** Girişte ve masalarda fiyat listesi; her fiyatın yanında "TL"; servis, kuver, masa ücreti alınmaz
(30 Ocak 2026 değişikliği). Kaynakların bir kısmı son fiyat değişikliği tarihinin de gösterilmesini istiyor.

**Sofra'da (Yönetim › Fiyat listesi):**

- Yazdırılabilir liste: işletme adı, şube ve adresi, kategoriler, her ürün "165,00 TL" biçiminde, altta
  "Fiyatlarımıza KDV dahildir.", "Servis, kuver veya masa ücreti alınmaz." ve **son fiyat değişikliği tarihi**.
  Kapı için A4, masa için A5. Liste her zaman Türkçe basılır. Şube misafir rehberinde görünüyorsa listede menüye
  giden QR kod da olur.
- Her ürünün ve her şube fiyatının **son değişiklik zamanı** tutulur (`price_changed_at`); listenin tarihi
  bunların en yenisidir. Bir şube merkez fiyata döndüğünde de tarihi korunur.
- Misafirin masa menüsü ve restoran sayfası altında "Fiyatlara KDV dahildir. Son fiyat değişikliği: …" yazar.
- **Ek ücret engeli:** Menüye "servis ücreti", "kuver", "masa ücreti", "hizmet bedeli", "cover charge" gibi adla
  ürün ya da seçenek girilemez (`MENU_CHARGE_NOT_ALLOWED`); "Kahvaltı servisi" gibi olağan adlar etkilenmez.

## 3. Elektronik fiyat listesi (Ticaret Bakanlığı)

Bakanlığın merkezi gönderim biçimi henüz yayımlanmadı (kaynaklar çelişiyor). Sofra şimdiden listenin tamamını
makine okunur verir:

- `GET /api/v1/price-list/export?format=csv`: Türk Excel düzeni (noktalı virgül, ondalık virgül, UTF-8 BOM):
  Şube; Kategori; Ürün; Fiyat (TL, KDV dahil); KDV oranı (%); Satışta; Son fiyat değişikliği.
- `GET /api/v1/price-list/export?format=json`: şema `sofra.price-list/1`, para birimi `TRY`, `vatIncluded: true`.

Biçim yayımlanınca bu dosya ya doğrudan gönderilir ya da o biçime dönüştürülür.

## 4. e-Adisyon (VUK 509)

**Yasa:** GİB'in bildirdiği yeme-içme işletmeleri her masa için elektronik adisyon tutar; bildirimden sonra en az
üç ay geçiş süresi verilir. Herkes için zorunlu değildir.

**Sofra'da:**

- Yalnızca e-Adisyon tutan işletmede çalışır: işletme Ayarlar › Bağlantılar › e-Adisyon'a entegratörünün adresini
  ve anahtarını girer (kendi sunucusunda `FISCAL_EADISYON_BASE_URL`, `FISCAL_EADISYON_API_KEY`).
- Masanın **ilk siparişiyle açılır**, eklenen/iptal edilen/ikram edilen her şeyle yeni bir sürüm olur, hesap
  ödenip **ÖKC fişi ya da e-Arşiv faturası kesilince o belgenin numarasıyla kapanır**; her şeyi iptal edilen masa
  "İptal" olur. Belgenin evrensel kimliği (ETTN) ömrü boyunca aynıdır.
- Her sürüm entegratöre işlem dışında gönderilir; ulaşmazsa 1, 2, 4… dakika arayla (en çok saatte bir, on deneme)
  yeniden denenir; en son sürüm her zaman kazanır. Kasa ekranında günün e-Adisyonları ve ulaşmayanlar görünür,
  elle yeniden gönderilebilir.
- Entegratör adaptörüyle sözleşme: `PUT {base}/adisyons/{ettn}`, gövdede sürüm, durum, masa, satırlar (KDV dahil
  fiyat ve oran), toplam, oran bazında KDV ve kapanış belgesinin numarası; `Idempotency-Key: {ettn}:{sürüm}`;
  yanıt `{"documentNumber": "..."}`. Entegratörün kendi API'si (GİB için imzalı UBL) bu biçime adaptörde eşlenir,
  e-Arşiv'deki gibi.
- Gerçek bir entegratöre karşı denenmedi; testler ve demo benzetimle çalışır (`restaurant.fiscal.simulate-e-adisyon`).

## Kalanlar

- Mali müşavir teyidi (oranlar, teslimat, indirim dağıtımı).
- Bakanlığın elektronik fiyat listesi biçimi yayımlanınca gönderim.
- e-Adisyon için gerçek entegratörle deneme (zorunluluk bildirilen ilk işletmeyle).
