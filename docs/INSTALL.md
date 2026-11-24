# Sofra kurulumu (restoranınız için)

Üç adım: bir komut, her cihazda bir adres, ilk giriş. Teknik bilgi gerekmez; sorularda Enter'a basmak önerilen
cevabı seçer. Takılırsanız en alttaki **Sorun giderme** bölümüne bakın.

*English summary at the end.*

## Hızlı kurulum

**1. Sunucuda tek komut** (restoranda sürekli açık kalan Linux bilgisayar; bkz. "Ne gerekir?"):

```sh
curl -fsSL https://raw.githubusercontent.com/UmutVarol/Restaurant-Order-Processing/main/deploy/get.sh | bash
```

Docker ve Git yoksa kurar, Sofra'yı indirir ve iki soru sorar: restoranın adı ve Sofra'ya nereden ulaşılacağı.
İkisinde de Enter yeterli (önerilen: yalnızca restoranın Wi-Fi'ı). Sonunda şunu yazar:

```
Sofra hazır:  https://192.168.1.20
  İlk yönetici:  kullanıcı adı  admin
                 geçici şifre   k7mq-x3ta-9pwe
  Tablet, telefon ve kasada şu adresi açın:  http://192.168.1.20
```

**2. Her cihazda o adresi açın** (`http://192.168.1.20`). Açılan sayfa cihazınızı tanır ve iki adımı gösterir:
"Sertifikayı indir" ve "Sofra'yı aç". Ardından tarayıcı menüsünden **Uygulamayı yükle / Ana Ekrana Ekle** deyin.
Sofra telefonda, tablette ve bilgisayarda aynı adresle, uygulama gibi tam ekran çalışır.

**3. `admin` ve geçici şifreyle girin.** Önce kendi şifrenizi belirlersiniz. Ardından **Yönetim › Kurulum**
ekranındaki listeyi izleyin: personel, masalar, menü, QR kartları, yazıcılar.

Hepsi bu. Bilgisayar yeniden başlasa bile Sofra kendiliğinden açılır ve her gece yedek alır.

## Ne gerekir?

| | |
|---|---|
| **Sunucu** | Restoranda sürekli açık kalan bir bilgisayar: mini PC, eski bir dizüstü ya da Raspberry Pi 4/5 (4 GB RAM ve üstü), Ubuntu veya Raspberry Pi OS ile. Windows ya da Mac kullanacaksanız: Docker Desktop ve Git'i kurun, sonra `git clone https://github.com/UmutVarol/Restaurant-Order-Processing.git sofra`, `cd sofra/deploy`, `./install.sh`. |
| **Ağ** | Kasa, tabletler ve telefonların bağlandığı Wi-Fi. Modem ayarlarından sunucuya **sabit adres** verin ("DHCP rezervasyonu"); adres değişirse cihazlar Sofra'yı bulamaz. |
| **Cihazlar** | Kasa için bilgisayar ya da tablet, garsonlar için telefon, mutfak için tablet. Ayrı uygulama indirmek gerekmez. |

## Kurulum seçenekleri

Kurulum ikinci soruda iki seçenek sunar:

- **1 – Yalnızca restoranın ağından** (önerilen, Enter). İnternete açılmaz. Misafirler masadaki QR ile aynı Wi-Fi
  üzerinden sipariş verebilir.
- **2 – İnternetten, bir alan adıyla** (ör. `siparis.restoraniniz.com`). Misafir telefonundan QR ile online ödeme,
  dışarıdan yönetim ve paket servis platformları için gerekir. Alan adı sunucuyu göstermeli ve modemde 80/443
  portları sunucuya yönlendirilmeli. Sertifika otomatik alınır, cihazlara bir şey yüklemek gerekmez.

Soru sormadan kurmak için: `./install.sh --name "Lokanta" --local 192.168.1.20 --yes` ya da
`--domain siparis.restoraniniz.com --email siz@ornek.com --yes`. Daha sonra seçeneği değiştirmek için aynı komutu
yeni seçenekle tekrar çalıştırın; veriler ve şifreler korunur.

Kurulumun yaptıkları:

- Rastgele, güçlü gizli anahtarlar üretip `~/sofra/deploy/.env` dosyasına yazar (yalnızca yönetici okuyabilir).
- Uygulamayı, veritabanını, HTTPS kapısını (Caddy) ve gece yedeklerini başlatır.
- Demo verilerini ve demo hesaplarını **kapatır**; API belgeleri ve metrikler dışarıya kapalıdır.
- İlk yöneticinin geçici şifresini bir kez gösterir ve diskte tutmaz.

> `.env` dosyasının bir kopyasını güvenli bir yerde (USB bellek, şifre yöneticisi) saklayın: sunucu bozulursa
> yedeği geri yüklemek için gerekir.

## İlk akşamdan önce (Yönetim › Kurulum)

- **İki adımlı giriş:** Ayarlar'dan açın (Google Authenticator gibi bir uygulamayla). Yöneticiler için zorunludur;
  iade, gün sonu ve kasa kaydı ondan önce çalışmaz.
- **Personel:** her çalışana bir hesap. *Garson* sipariş ve ödeme alır, *Mutfak* fişleri görür, *Yönetici* her şeyi
  görür. Ekranda bir kez çıkan geçici şifreyi çalışana verin; ilk girişte kendi şifresini belirler. İşten ayrılanın
  hesabını kapattığınızda oturumu **o anda** her cihazda kapanır.
- **Masalar:** 12 örnek masayla gelir; numara, kişi sayısı ve bölümü salonunuza göre düzenleyin, fazlasını kaldırın.
- **Menü:** örnek menüyü kendi yemekleriniz ve fiyatlarınızla değiştirin (Yönetim › Menü).
- **QR kartları:** Ayarlar › Masa QR kodları'ndan basıp masalara koyun.
- **Yazıcılar** (isteğe bağlı): ağ fiş yazıcısının adresini Ayarlar › Yazıcılar'a girip "Test"e basın.
- Garsonlar Ayarlar'dan bir **PIN** belirleyip kayıtlı kasada PIN ile hızlı geçiş yapabilir.

## Yedekler

Her gece saat 03:00'te (UTC, Türkiye saatiyle 06:00) veritabanının yedeği `deploy/backups/` klasörüne alınır. Her
yedek okunabilirliği kontrol edildikten sonra sayılır; 14 günden eski yedekler silinir.

- **Yedekleri başka bir yere de kopyalayın.** Aynı diskteki yedek, disk bozulunca onunla gider. En basit yol: her
  gün klasörü bir USB diske ya da bulut depolamaya kopyalayan bir zamanlanmış görev.
- **Hemen yedek almak:** `docker compose run --rm --entrypoint sofra-backup backup`
- **Ayda bir geri yükleme provası** yapın: [OPERATIONS.md › Restoring](OPERATIONS.md#restoring).

## Güncelleme

```sh
cd ~/sofra && git pull && cd deploy && ./update.sh
```

`update.sh` önce bir yedek alır, sonra yeni sürümü kurar ve Sofra'nın sağlıklı açıldığını kontrol eder. Veritabanı
değişiklikleri kendiliğinden uygulanır. Hazır imaj kullanmak için (derleme beklemeden, Raspberry Pi için de):
`./update.sh 1.2.3` (sürümler GitHub'daki **Releases** sayfasındadır).

Bir sorun olursa: `docker compose logs app` hatayı gösterir. Geri dönmek için az önce alınan yedeği geri yükleyip
önceki sürüme geçin (`./update.sh <önceki sürüm>`).

## Ödeme, yasal belgeler ve paket servis

Bunlar `deploy/.env` dosyasında açılır; değiştirdikten sonra `docker compose up -d` çalıştırın.

| | Ayar |
|---|---|
| **QR ile online ödeme (iyzico)** | `PAYMENT_PROVIDER=IYZICO`, `IYZICO_API_KEY`, `IYZICO_SECRET_KEY` (iyzico üye işyeri panelinden). İnternet (alan adı) kurulumu gerekir. Ayrıntılar: [PAYMENTS.md](PAYMENTS.md). |
| **ÖKC fişi** | ÖKC'nizin üreticisinden alınan köprü yazılımının adresi ve anahtarı: `FISCAL_OKC_BRIDGE_URL`, `FISCAL_OKC_API_KEY`. |
| **e-Arşiv fatura** | GİB yetkili özel entegratörünüzün bilgileri: `FISCAL_EARSIV_BASE_URL`, `FISCAL_EARSIV_API_KEY`. |
| **e-Adisyon** | Yalnızca GİB e-Adisyon zorunluluğu bildirdiyse: entegratörünüzün `FISCAL_EADISYON_BASE_URL`, `FISCAL_EADISYON_API_KEY` bilgileri. |
| **Yemeksepeti / Getir / Trendyol** | Platformla yapılan sözleşmeden sonra verilen anahtarlar: `YEMEKSEPETI_WEBHOOK_SECRET` vb. (README'deki Configuration bölümü). |

Bu entegrasyonlar ayarlanmadıysa Sofra o belgeleri **kesmez** ve bunu açıkça gösterir. Yasal fişi o arada yazar
kasadan kesmeye devam edin.

## Güvenlik özeti

- Tüm bağlantılar HTTPS; uygulama ve veritabanı dışarıya hiç açılmaz, yalnızca Caddy açılır.
- Demo hesapları ve demo verisi yok; ilk yönetici şifresi geçicidir ve ilk girişte değiştirilir.
- Yöneticiler için iki adımlı giriş zorunlu; para ile ilgili işlemler yakın zamanda şifre ister.
- Hesap kapatıldığında, görevi değiştiğinde ya da şifresi sıfırlandığında oturumları anında biter.
- Ayrıntılar: [SECURITY.md](../SECURITY.md). İzleme ve uyarılar: [OPERATIONS.md](OPERATIONS.md).

## Sorun giderme

| Belirti | Çözüm |
|---|---|
| Tablet "bağlantı güvenli değil" diyor | Cihazda `http://<sunucu adresi>` sayfasını açıp sertifikayı yükleyin. Sunucunun adresi değiştiyse modemde sabit adres verip `./install.sh --local <yeni adres>` çalıştırın; sertifikayı cihazlara yeniden yükleyin. |
| Alan adıyla açılmıyor | Alan adının DNS kaydı sunucunun dış IP'sini göstermeli; modemde 80 ve 443 portları sunucuya yönlendirilmeli. `docker compose logs caddy` sertifika hatasını gösterir. |
| Giriş şifresini unuttum | Başka bir yönetici Kurulum › Personel'den şifreyi sıfırlar. Tek yönetici sizseniz sunucuda `cd ~/sofra/deploy && ./reset-admin.sh` çalıştırın: `admin` hesabına yeni bir geçici şifre verir. |
| Yazıcı basmıyor | Yazıcının ağ adresini (genelde yazıcıdan bir test fişi basılarak öğrenilir) Ayarlar › Yazıcılar'da kontrol edin, "Test"e basın. Mutfak ekranı her siparişi yine gösterir. |
| Durumu görmek | `docker compose ps` (hepsi "healthy" olmalı), `docker compose logs app --tail 100`. |

---

## English summary

1. A machine that stays on (mini PC, laptop, Raspberry Pi 4/5 with 4 GB, or a small cloud server) with Docker and Git.
2. `curl -fsSL https://raw.githubusercontent.com/UmutVarol/Restaurant-Order-Processing/main/deploy/get.sh | bash`
   (Linux; installs Docker and Git if missing) or `git clone … sofra && cd sofra/deploy && ./install.sh`
   asks the restaurant's name and whether Sofra is reached on the local network only (Caddy's local certificate
   authority: every device opens `http://<address>`, a welcome page that hands out the certificate and links to
   Sofra) or from the internet with a domain (Let's
   Encrypt). It writes `.env` with fresh secrets, starts the app, PostgreSQL, Caddy and nightly backups, and prints
   the first manager's temporary password, which must be replaced at the first sign-in.
   Non-interactive: `./install.sh --name "Lokanta" --domain sofra.example.com --email you@example.com --yes` or
   `--local 192.168.1.20`; `--image ghcr.io/umutvarol/restaurant-order-processing:x.y.z` uses a released image.
3. Manage › Setup lists what is left: two-step sign-in, staff accounts, tables, menu, QR cards, printers.
4. Install Sofra as an app (PWA) on phones, tablets and desktops from the browser menu.
5. Backups land in `deploy/backups/` nightly; copy them elsewhere. Update with `git pull && ./update.sh`
   (backup first, health check after) or `./update.sh x.y.z` for a released image.
