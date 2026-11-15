# PS5 แปลไทย (PS5 Thai Translator)

เล่นเกม PS5 บนทีวี Android TV / Google TV ผ่านเน็ตบ้าน พร้อมแปลซับและเสียงพูดภาษาอังกฤษในเกมเป็นภาษาไทยขึ้นบนจอแบบเรียลไทม์ ไม่ต้องใช้คอม ไม่ต้องมีการ์ดแคปเจอร์ ไม่ต้องเจลเบรก

Play PS5 games on an Android TV / Google TV over your home network, with live English → Thai translation of on-screen subtitles and spoken dialogue drawn over the game.

## ดาวน์โหลด

**[โหลดเวอร์ชันล่าสุด (PS5-Thai-Translator.apk)](https://github.com/midnon80-create/ps5-thai-translator/releases/latest/download/PS5-Thai-Translator.apk)**

ลิงก์นี้ชี้ไปที่เวอร์ชันล่าสุดเสมอ ใช้ทำรหัสในแอป Downloader บนทีวีได้

## ทำอะไรได้

- เล่น PS5 บนทีวีผ่าน Wi-Fi / สาย LAN ใช้จอย PS5 ที่จับคู่กับทีวี
- **อ่านซับ / กล่องข้อความบนจอ** แล้วแปลไทย วางชิดเหนือซับอังกฤษอัตโนมัติ
- **ฟังเสียงพูด** ในเกมแล้วแปลไทย (สำหรับฉากที่ไม่มีซับ)
- แปลบนเครื่องทีวีเอง ฟรีและไม่จำกัด (ML Kit) หรือใส่ Gemini API key เพื่อภาษาที่ลื่นขึ้น
- ฟอนต์ไทยฝังมาในแอป (Sarabun / Kanit) หน้าตาเหมือนกันทุกทีวี
- ล็อกอิน PSN ในแอปเพื่อจับคู่กับ PS5 ไม่ต้องใช้คอม
- เช็กและติดตั้งอัปเดตได้ในแอป
- ปุ่ม "ทดสอบตัวแปล" เช็กทีวีได้โดยไม่ต้องเปิด PS5

## ทีวีที่ใช้ได้

| ทีวี | ใช้ได้ไหม |
|---|---|
| Google TV / Android TV (Android 7+) เช่น Sony, TCL, Xiaomi, Sharp, Hisense/Philips รุ่น Google TV | ✔ ได้เลย |
| Samsung, LG, Panasonic, VIDAA, Roku | ต้องเสียบดองเกิล Google TV (เช่น Google TV Streamer, Xiaomi TV Box S) |

## ข้อจำกัด

- PS5 กับทีวีต้องอยู่เน็ตบ้านเดียวกัน
- คำแปลช้ากว่าเกมประมาณ 0.5–2 วินาที
- แปลจากภาษาอังกฤษเท่านั้น
- ภาพสูงสุด 1080p ไม่มี HDR

## Building

Based on [chiaki-ng](https://github.com/streetpea/chiaki-ng) (original README: [README-chiaki-ng.md](README-chiaki-ng.md)).
The translator lives in `android/app/src/main/java/com/metallic/chiaki/translate/` and `android/app/src/main/cpp/audio-tap.c`.

Built on Windows with JDK 17, Android SDK 35, NDK 28.2.13676358, CMake 3.22.1 and `protoc` on `PATH`:

```
cd android
gradlew assembleRelease
```

The Windows build uses mbedtls instead of OpenSSL and copies headers instead of creating symlinks.
LAN play only (internet remote play is off in this build).

## License & credits

GNU AGPL v3 — see [COPYING](COPYING) and [LICENSES](LICENSES). Based on Chiaki by Florian Märkl and chiaki-ng by Street Pea and contributors.
Speech recognition: [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache 2.0) with [Moonshine Tiny](https://github.com/usefulsensors/moonshine) (MIT, Useful Sensors) and [Silero VAD](https://github.com/snakers4/silero-vad) (MIT); the model is downloaded on first use from the `speech-model-1` release. OCR and offline translation: Google ML Kit.
Fonts: Sarabun and Kanit (SIL Open Font License). Offline game phrase bank: `android/app/src/main/assets/phrasebook_th.txt`.

**ไม่ได้เป็นผลิตภัณฑ์ของ Sony Interactive Entertainment / PlayStation**
This project is not affiliated with, endorsed by, or sponsored by Sony Interactive Entertainment. "PlayStation" and "PS5" are trademarks of Sony Interactive Entertainment Inc.
