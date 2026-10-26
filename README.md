# AcuMedis Android

App Android native untuk [AcuMedis](https://github.com/djvpri/z_medics) — rekam medis klinik TCM.

Kotlin + AppCompat, tanpa dependensi jaringan eksternal (`HttpURLConnection`), login memakai
NextAuth credentials milik server AcuMedis (cookie-based).

## Fitur v1.0.0

- **Login** — alamat server + email + password (NextAuth `/api/auth/csrf` → `/api/auth/callback/credentials`)
- **Beranda** — ringkasan jadwal hari ini (total, selesai, menunggu, belum bayar) + antrean berikutnya dengan nomor antrean
- **Pasien** — daftar, cari nama/telepon, tambah pasien
- **Rekam medis** — riwayat terapi per pasien, total sesi, status pembayaran
- **Sesi terapi baru** — keluhan, warna lidah, skala nyeri, titik terapi (chip), durasi, catatan, biaya, status bayar

## Konsep desain

Swiss minimal, cyan kesehatan `#0E7490`, teks `#164E63`, font Figtree (web) → sistem sans di app.
Ikon SVG (vector drawable), target sentuh ≥ 48dp, kontras teks ≥ 4.5:1.

## Build

Butuh JDK 17 (bukan JBR Android Studio yang JDK 25) dan Android SDK.

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew assembleDebug       # atau assembleRelease
```

Alamat server diisi saat runtime di layar login, atau sebagai default build:
`~/.gradle/gradle.properties` → `ACUMEDIS_BASE_URL=https://domain`.

Test:

```bash
./gradlew testDebugUnitTest
```

## Struktur

```
app/src/main/java/com/acumedis/app/
  Api.kt                  HTTP client + cookie NextAuth + model Patient/Visit + format tanggal
  LoginActivity.kt        login server + kredensial
  MainActivity.kt         beranda: ringkasan jadwal + antrean
  PatientsActivity.kt     daftar/cari/tambah pasien
  PatientDetailActivity   riwayat terapi pasien
  SessionFormActivity     input sesi terapi baru
```

## Keamanan

- Token/sesi disimpan di `SharedPreferences` privat aplikasi
- Tidak ada kredensial yang di-hardcode di repo
- Semua request lewat HTTPS (server wajib `https://`)
