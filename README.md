# CastWave — BubbleUPnP-style DLNA/UPnP caster (Kotlin)

Cast local audio, video and photos from your phone to Smart TVs, DLNA renderers (VLC, WMP, Kodi…) and speakers.

* **SSDP discovery** over UDP multicast `239.255.255.250:1900`, on every LAN interface (Wi-Fi *and* hotspot), with a `MulticastLock`.
* **Embedded HTTP media server** (pure `ServerSocket`, no libraries): GET/HEAD, byte ranges for seeking, MIME types, DLNA headers.
* **DLNA controller**: SOAP `SetAVTransportURI`, `Play`, `Pause`, `Stop`, `Seek`, `GetTransportInfo`, `GetPositionInfo`, `SetVolume`, `GetVolume`.
* **Media browser**: Audio / Videos / Photos via MediaStore (Android 13+ granular permissions + legacy fallback) and Files via the system picker (SAF).
* **Foreground service** (`mediaPlayback`) with wake lock, Wi-Fi lock and an ongoing notification (Play/Pause, Stop, Turn off).

## Build

    ./gradlew assembleDebug          # APK: app/build/outputs/apk/debug/app-debug.apk

Pushing to GitHub runs `.github/workflows/build.yml`, which uploads the APK as a workflow artifact and
publishes it as a pre-release asset (`CastWave-debug.apk`).

Requirements: JDK 17, Android SDK 34. Dependencies are only AndroidX, Material and kotlinx-coroutines.

## Xiaomi / HyperOS tips
Menu → *Background / battery settings*: set Battery saver to **No restrictions** and enable **Autostart**,
otherwise the OS may kill the streaming service when the screen is off.
