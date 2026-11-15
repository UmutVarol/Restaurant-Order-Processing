# Yazıcı köprüsü

Mutfak ve bar fişleri ağ yazıcılarına (ESC/POS, genelde 9100 portu) gider. Sofra restoranın kendi bilgisayarında
çalışıyorsa yazıcılara kendisi bağlanır. **Sofra Cloud**'da ise sunucu internette olduğu için restoranın yerel
ağındaki yazıcıya ulaşamaz; bu işi restoranda çalışan küçük bir program, **yazıcı köprüsü** yapar.

- Köprü yalnızca **dışarıya** bağlanır (HTTPS). Modemde port açmak, sabit IP almak gerekmez.
- Her köprü bir şubeye aittir ve yalnızca o şubenin fişlerini görür.
- Köprü fişleri yalnızca **yerel ağdaki** adreslere (192.168.x.x, 10.x.x.x, 172.16-31.x.x) ve yazıcı portlarına
  (9100-9109) gönderir. İnternetteki bir adrese ya da bulut sağlayıcılarının iç adreslerine asla bağlanmaz.
- Yazıcı kapalıysa ya da kâğıdı bittiyse köprü hatayı Sofra'ya bildirir; fiş birkaç kez yeniden denenir ve
  yazıcının hatası Ayarlar'da görünür.

## 1. Sofra'da köprüyü ekleyin

1. **Ayarlar › Mutfak ve bar yazıcıları › Yazıcı köprüsü ekle** (zincirlerde önce üstten şubeyi seçin).
2. Ekranda çıkan **adres** ve **anahtar** bir kez gösterilir; kopyalayın. Kaybolursa köprüyü kapatıp yenisini
   eklemeniz yeterli.
3. Yazıcıları eklerken (ya da düzenlerken) **"Yazıcı köprüsü yazdırır"** seçeneğini işaretleyin. Bulutta bu her
   yazıcı için zaten zorunludur.

## 2. Köprüyü restorandaki bilgisayarda çalıştırın

Sürekli açık duran herhangi bir bilgisayar yeter: kasa bilgisayarı, mini PC ya da Raspberry Pi. Bilgisayar yazıcılarla
**aynı ağda** olmalı. Gerekli tek şey **Node.js 18 veya üstü** (nodejs.org).

Programın tamamı tek bir dosyadır: `bridge/sofra-print-bridge.mjs`.

**Önce deneyin** (bir test fişi yazdırıp kapanır; Ayarlar'da yazıcının yanındaki "Test" ile bir fiş isteyin):

```bash
SOFRA_URL=https://isletmeniz.sofra.app SOFRA_BRIDGE_TOKEN=<anahtar> node sofra-print-bridge.mjs --once
```

**Linux / Raspberry Pi'de sürekli çalıştırma** (açılışta kendiliğinden başlar, düşerse yeniden başlar):

```bash
sudo mkdir -p /opt/sofra && sudo cp sofra-print-bridge.mjs /opt/sofra/
sudo cp sofra-print-bridge.service /etc/systemd/system/
sudo systemctl edit sofra-print-bridge     # SOFRA_URL ve SOFRA_BRIDGE_TOKEN satırlarını kendi değerlerinizle yazın
sudo systemctl enable --now sofra-print-bridge
journalctl -u sofra-print-bridge -f        # kayıtları izlemek için
```

**Windows'ta:** Görev Zamanlayıcı'da "Bilgisayar açıldığında" tetiklenen bir görev oluşturun; eylem:
`node C:\Sofra\sofra-print-bridge.mjs`, ortam değişkenlerini (SOFRA_URL, SOFRA_BRIDGE_TOKEN) sistem ayarlarından
ekleyin. Bilgisayarın uykuya geçmemesini ayarlayın.

## Sorunlar

| Görülen | Ne yapmalı |
|---|---|
| Ayarlar'da köprü "Henüz bağlanmadı" ya da kırmızı | Köprünün çalıştığını ve internete çıkabildiğini kontrol edin. |
| Kayıtta "the bridge token was refused" | Köprü Ayarlar'dan kapatılmış ya da anahtar yanlış; yeni köprü ekleyin. |
| "is not on the local network" | Yazıcının adresi yerel ağ adresi değil; yazıcının IP'sini (yazıcının kendi test sayfasında yazar) girin. |
| "ECONNREFUSED" / "did not answer in time" | Yazıcı kapalı, kablosu çıkmış ya da adresi değişmiş. Yazıcıya sabit IP verin. |

Ayarlar: `SOFRA_PRINTER_PORTS` (varsayılan `9100-9109`) izin verilen yazıcı portlarını değiştirir.
