# Vergininkolesi

Java + JDA + Lavalink v4 muzik botu.

## Ozellikler

- Discord slash komutlari: `/play`, `/skip`, `/stop`, `/pause`, `/resume`, `/nowplaying`, `/queue`, `/volume`, `/leave`
- YouTube arama ve direkt video/playlist linkleri
- Spotify/Apple Music/Deezer linkleri icin Lavalink LavaSrc destegi
- Sunucu/guild id istemez; komutlar global kaydolur ve guild id runtime'da otomatik alinir
- `.env` ile token ve Lavalink node ayari
- Wispbyte icin tek dosya fat jar: `build/libs/vergininkolesi-1.0.0-all.jar`

## Kurulum

1. `.env` dosyasinda `BOT_TOKEN` degerini doldur.
2. Lavalink node ayarlarini ayni dosyada sunucuna gore duzenle.
3. Jar olustur:

```bash
./gradlew build
```

4. Botu calistir:

```bash
java -jar build/libs/vergininkolesi-1.0.0-all.jar
```

## .env

```env
BOT_TOKEN=discord_bot_token_buraya
LAVALINK_NAME=primary
LAVALINK_URI=ws://localhost:2333
LAVALINK_PASSWORD=youshallnotpass
LAVALINK_REGION=EUROPE
LAVALINK_AUTOSTART=true
LAVALINK_JAR=Lavalink.jar
LAVALINK_STARTUP_DELAY_MS=45000
DEFAULT_VOLUME=80
```

`client id` veya sabit `guild id` gerekmez.

## Lavalink

Zip paketinde `Lavalink.jar` ve `application.yml` varsa bot baslarken Lavalink'i otomatik baslatir.
Disaridan ayri Lavalink kullanacaksan `LAVALINK_AUTOSTART=false` yap.

Wispbyte startup dosyasi olarak `index.js` kullanilabilir. Bu dosya jar yoksa once `./gradlew build`
calistirir, jar varsa direkt `java -jar build/libs/vergininkolesi-1.0.0-all.jar` ile botu baslatir.

Spotify Premium benzeri deneyim icin LavaSrc Spotify metadata'sini cozer ve sesi provider listesiyle esler.
Spotify URL'leri direkt Spotify sesini stream etmez; Lavalink/LavaSrc tarafinda YouTube veya Deezer gibi playable kaynaklardan esleme yapar.

YouTube tarafinda Lavalink'in kendi eski YouTube kaynagi kapali tutuldu; `youtube-source` plugin'i kullaniliyor.
