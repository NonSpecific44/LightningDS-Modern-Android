# Lightning DS for modern Android

A rebuild of AURALiC's **Lightning DS** control app (v1.1, 2015) for modern Android phones and tablets.
It controls AURALiC Lightning streamers (ARIES, ARIES LE, ARIES MINI, …), Lightning Server, and other
OpenHome and UPnP/DLNA players and media servers on your network.

> Community project, not affiliated with or endorsed by AURALiC.

## Why the original no longer works

Lightning DS 1.1 ran all device communication through a native library (`libIptOhNet.so`, AURALiC's
build of the OpenHome *ohNet* stack) that only existed for **32‑bit ARMv5 (`armeabi`)**. Modern devices
are 64‑bit only and can't load it. The app also targeted Android 5.1, was locked to portrait, and relied
on long-retired APIs.

This version replaces the native code with a **pure Kotlin UPnP/OpenHome implementation**, so it runs on
any Android 8.0+ device, phone or tablet, on every CPU architecture.

## Features

Everything from the original app, rebuilt with Material 3 (Jetpack Compose):

| Area | What you can do |
|---|---|
| **Devices** | Automatic discovery (SSDP), player and library picker, passcode‑locked devices, standby, source switching |
| **Now playing** | Artwork, track info, codec / bit depth / sample rate / bitrate, seek, play/pause/next/previous, shuffle, repeat, volume and mute |
| **Queue** | View, play any entry, reorder, remove, clear, save as playlist |
| **Music library** | Imports the Lightning Server (or any DLNA server) library and browses it by Album, Artist, Composer, Genre, Date, File type, Sampling rate or Song, A→Z / Z→A; folder browsing; Play now / Add next / Add later / Replace queue |
| **Metadata editing** | Fix album title, artist, year, genre, track titles/numbers and cover art; look albums up on MusicBrainz with cover art from the Cover Art Archive; choose a cover from the phone or an image URL; *Fix missing info* lists incomplete albums and can find missing covers automatically. Edits are stored in the app (servers don't accept metadata changes) |
| **Collection & playlists** | Star albums into your Collection; create, rename, reorder and delete playlists |
| **Streaming** | TIDAL (successor of WiMP) and Qobuz: featured, genres, favorites, playlists, purchases (Qobuz), search, album/artist/playlist pages, add/remove favorites |
| **Radio** | Most recent, favorites, popular, genres, location and search |
| **Inputs** | AirPlay and Songcast switches, plus every other input on the device |
| **Search** | Library, streaming and radio together, with search history |
| **Lightning device settings** | Name, passcode, operating mode (OpenHome / UPnP renderer), network (wired/wireless, DHCP or manual IP, Wi‑Fi selection), DAC output delay, output channel, native DSD, software volume control, device info, firmware update |
| **Lightning Server settings** | Name, version, storage (USB / shared network folder + SMB credentials), drive status, mount/unmount, rescan |
| **Setup guide** | Step‑by‑step device setup (product, cables, finding the device, name, output, volume control, DSD, activation, firmware) and music library setup |
| **General** | Light/dark/system theme, dynamic color, sorting, text alignment, English and 简体中文, keep screen on |

### Tablets and large screens

The layout adapts to the window size: a bottom navigation bar on phones, a navigation rail or drawer on
tablets and foldables, grids that scale with width, side‑by‑side album pages, and on large screens a
permanent **Now playing / Queue** pane next to whatever you're browsing. Rotation is fully supported.

## Things that work differently

* **Internet radio.** The original used vTuner, which requires AURALiC's partner key. Stations now come
  from the free, community‑run [radio-browser.info](https://www.radio-browser.info) directory.
* **Streaming credentials.** TIDAL and Qobuz only allow registered applications to sign in. The keys
  embedded in the 2015 app belong to AURALiC (and the WiMP API they were for no longer exists), so they
  aren't included here. Enter your own **TIDAL client ID** or **Qobuz app ID** in
  *Settings → Streaming services*. TIDAL uses its device‑code sign‑in (you approve the login in a
  browser).
* **Streaming playback** works the way it always did: the app passes a `Streaming://TIDAL?...` or
  `Streaming://Qobuz?...` address to the Lightning device, which fetches the music itself. Whether this
  still works depends on your device's firmware supporting today's TIDAL/Qobuz services.
* **Plain UPnP/DLNA renderers** have no device-side queue, so for those the queue is kept in the app.
* **Feedback / user guide** link to this repository instead of AURALiC's old pages.

## Installing

Download the APK from the latest successful run of the **Build APK** workflow
(*Actions → Build APK → Artifacts → `LightningDS-apk`*), copy it to your device and open it
(allow installing from unknown sources when asked).

* `app-release.apk` – optimized build, package `com.lightningds.modern`.
* `app-debug.apk` – debug build, package `com.lightningds.modern.debug` (installs alongside).

Both are signed with the public key in `app/debug.keystore`, so newer builds install over older ones.
Use your own keystore if you redistribute the app.

Your phone must be on the same Wi‑Fi/LAN as your Lightning device.

## Building

Requirements: JDK 17+, Android SDK with platform 36.

```sh
./gradlew :core:test            # protocol engine unit tests (plain JVM)
./gradlew :app:assembleRelease  # APK in app/build/outputs/apk/release/
```

## Project layout

```
core/   Pure Kotlin/JVM: SSDP discovery, SOAP control, GENA eventing, OpenHome and UPnP AV
        renderers, AURALiC HardwareConfig/ServerConfig, DIDL-Lite, library import,
        TIDAL/Qobuz clients, radio directory. Unit tested against a fake device.
app/    Android app (Jetpack Compose, Material 3, adaptive navigation).
```

The control protocol was recovered from the original APK: the service and action names used by the
AURALiC‑specific `HardwareConfig` and `ServerConfig` services come from its native library, and the
app reads each device's own service description at runtime to match argument names exactly.
