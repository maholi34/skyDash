# Skywell ET5 — SkyDash Projesi Teknik Dokümantasyonu

## 1. Araç ve Donanım Bilgileri
- **Marka / Model:** SkyWell ET5 (BE11 / `borong_be11`)
- **Android Sürümü:** Android 9 (API 28) - `MEK-MX8Q` (i.MX8Q)
- **Ekran:** 1786×960 px, 213 dpi, 60 Hz
- **Sistem Adı:** COOCAAOS · MCU: SW:c.02.19

---

## 2. Veri Okuma Yöntemleri & LocalADB

### 2.1 LocalADB Tekniği
Uygulama kendi cihazında (`127.0.0.1:5555`) yerel bir ADB istemcisi gibi soket bağlantısı kurar (`LocalAdbClient.java`).
- `adbd` servisi cihazda `uid=2000` (shell) yetkisiyle `5555` portunu dinlediğinden, root iznine takılmadan `dumpsys car_service` veya HAL verileri okunabilir.
- `VehicleHal` üzerinden 225 property (SoC %, Menzil, Hız, Odometre, Tüketim, Sıcaklıklar vb.) doğrudan sorgulanabilir.

### 2.2 ContentProvider Tabanlı Okuma (Root'suz)
- `content://com.coolwell.ai.skyhvac.database/airconditioner` -> HVAC (Klima, Fan, İç/Dış Sıcaklık) verileri.
- `content://com.skyworth.car.aisettings.vehiclecontrol.database/recovery` -> Rejen seviyeleri.

---

## 3. Tasarım Sistemi ("Gece Kokpiti")
- **Renk Paleti:** Zemin `#0A0F1C`, Paneller `#111928`, Vurgu `#5AD8C2` (Teal), Uyarı `#F0A857` (Amber), Metin `#ECF1F8`.
- **Performans:** GPU yükünü azaltmak için gürültüsüz, blur ve gölgesiz sade vektörel tasarım.
- **Ekranlar:**
  1. **Gösterge Paneli:** Batarya SoC %, menzil, canlı enerji akışı, kabin klima durumu.
  2. **Sürüş Geçmişi:** Son sürüş, önceki sürüş, toplam mesafe ve tüketim (kWh/100km).
  3. **Ana Menü:** Modül yönetim paneli.

---

## 4. Sıradaki Adımlar
1. **LocalAdb testinin araçta koşturulması** (`SkywellDashboard` / `SkywellRootTest` ile).
2. GitHub Actions ile APK derleme ortamının kurulması (`.github/workflows/build-apk.yml`).
3. Sürüş ve şarj veri saklama mantığının (`TripStore`) entegrasyonu.
