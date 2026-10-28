# Kurye uygulaması (Android) – kurulum

1. `capacitor.config.json` içindeki `https://SITENIZ.netlify.app/kurye.html` adresini kendi sitenle değiştir.
2. github.com'da ücretsiz hesap aç, **private** yeni bir depo (repository) oluştur, bu klasördeki her şeyi (`.github` klasörü dahil) depoya yükle.
3. Depoda **Actions → Android APK → Run workflow**. 5-10 dk sonra bitince çalıştırma sayfasının altındaki **kurye-apk** dosyasını indir (içinden `app-debug.apk` çıkar).
4. APK'yı kuryelerin telefonuna gönder, aç ("bilinmeyen kaynaklardan yükleme"ye izin ver).
5. İlk açılışta: Konum izni → İzin ver, Bildirim izni → İzin ver. Pil ayarlarında uygulamayı "Kısıtlama yok / Optimize etme" yap.

Kurye ekranı Netlify'dan yüklenir: `kurye.html` değişince uygulamayı yeniden kurmak gerekmez.

## Konum uygulama kapalıyken de çalışır (v2)
Bu sürümde konumu telefonun kendi servisi (`TrackerService`) gönderir: ekran kapanınca, uygulama arka plana alınınca ya da uygulama
kapatılınca da vardiya boyunca çalışır; vardiyayı bitirince ya da çıkış yapınca durur. İlk açılışta **konum → "Her zaman izin ver" → bildirim**
izinleri istenir. Pil ayarında uygulamayı "Kısıtlama yok" yap. Telefon yeniden başlarsa servis vardiya açıksa kendiliğinden başlar.
Ayarlardan "Zorla durdur" yapılırsa (Android kuralı) servis de durur; kurye uygulamayı yeniden açmalı.
