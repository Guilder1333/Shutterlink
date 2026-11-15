# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Shutterlink is an Android app (Kotlin + Jetpack Compose) that talks to Panasonic Lumix cameras over Wi‑Fi, replacing the official Image App for older models. Developed against the G85 and GM1; other models are untested. Single module `:app`, package `net.cheeseverse.shutterlink`, minSdk 33 / target+compile SDK 35. User-facing setup steps (camera menu flow) are in `getting_started.md`; known bugs are listed in `Readme.md`.

## Build & test

The Gradle wrapper is incomplete: `gradlew` is committed but `gradle/wrapper/` (jar + properties) is not, so `./gradlew` fails as-is. Either use a system Gradle (AGP 8.8.0 needs Gradle ≥ 8.10.2 and JDK 17) or regenerate the wrapper with `gradle wrapper`. An Android SDK (`ANDROID_HOME` or `local.properties` with `sdk.dir`) is required.

```bash
gradle :app:assembleDebug                 # build debug APK
gradle :app:installDebug                  # install on connected device
gradle :app:testDebugUnitTest             # JVM unit tests
gradle :app:testDebugUnitTest --tests "net.cheeseverse.shutterlink.ExampleUnitTest"   # single test class
gradle :app:connectedDebugAndroidTest     # instrumented tests (device/emulator needed)
gradle :app:lint                          # Android lint
```

The only tests are the template `ExampleUnitTest` / `ExampleInstrumentedTest`. Real behaviour can only be verified against a physical camera; the app logs heavily via `println`, so use logcat for debugging.

Version coupling: Kotlin plugin 1.9.0 is pinned to Compose compiler extension 1.5.1 (`composeOptions` in `app/build.gradle.kts`). Bumping one requires bumping the other per the Compose–Kotlin compatibility map.

## Architecture

Essentially all logic lives in `app/src/main/java/net/cheeseverse/shutterlink/MainActivity.kt`: networking/protocol code at the top, Compose UI (`initBox`, `PicList`) below the `//UI Below` marker. `ui/theme/` is the stock Compose theme.

**Global mutable state.** Connection state is held in top-level `var`s (`cameraIPAddress`, `deviceIPAddress`, `cameraMode`, `curIndex`, `numPicsOnCamera`, `requestedPages`, `picReceiverConnectionStage`, `deviceUUID`), shared across coroutines started with `GlobalScope.launch`. Several functions busy-wait on these (e.g. `while(cameraIPAddress == "") {}`). The keep-alive loop resets them to their initial values when the camera stops responding. This is why rotating the screen (Activity recreation) leaves a live connection with a fresh UI.

**Device identity.** `deviceUUID` is a fixed Panasonic-style prefix plus a random suffix, persisted in the Activity's SharedPreferences (`persistUUID`) so the camera recognises the phone across sessions. On launch the app opens the system Wi‑Fi panel so the user can join the camera's hotspot (typically `192.168.54.x`; `getIPAddress()` prefers that subnet).

There are two connection modes, chosen in the startup dialog:

1. **Download images from camera (phone is the client).**
   - SSDP discovery (`ackDiscoveryPackets` + `constantlyAdvertiseSSDP`, multicast `239.255.255.250:1900`) finds the camera's IP.
   - `getCameraIntoPlaybackMode` calls the camera's HTTP control API on port 80: `cam.cgi?mode=accctrl…` (pair), `getsetting&type=pa`, then `camcmd&value=playmode`. `doKeepAlive` polls `getstate` every 500 ms and parses `<cammode>`.
   - Listing uses UPnP ContentDirectory SOAP `Browse` on `:60606/Server0/CDS_control`, paged by `imagesPerRequest` (21) and indexed from the newest image backwards.
   - Media is served on port 50001 under a filename-prefix convention. `DT*` is the grid thumbnail, `DL*` the larger preview (long-press), and `DO*` the original (tap). Videos are fetched by retrying the `DO` name with `.mp4` after a 404 on `.JPG`.
   - Thumbnails are cached in `cacheDir`, which is wiped in `onStop`. Full downloads go to MediaStore under `DCIM/Shutterlink`.
   - Volume keys trigger `captureImage()` (switches to `recmode` if needed, then `camcmd&value=capture`) once `cameraMode` is non-empty.

2. **Background link / auto-send (phone acts as a DLNA media server the camera uploads to).**
   - `registerAsReceiver` starts three servers plus a pairing request:
     - `listenForControlRequests` on TCP 49152 serves the UPnP device description `/dms/ddd.xml`, which impersonates `LUMIX_UP_SERVER_SMP`. It also answers SOAP on `/dms/control_0`: `X_GetDLNAUploadProfiles`, which sets `picReceiverConnectionStage = 4` and stops the SSDP advertising, and `CreateObject`, which returns an `importUri` pointing at port 49153.
     - `imageServer` on TCP 49153 accepts the camera's chunked HTTP upload. It replies `100 Continue`, decodes the chunks with `getContentLengthFromStream`, and writes the image to MediaStore under `DCIM/Shutterlink Live Transfer`. Only connections from `cameraIPAddress` on a site-local address are accepted.
     - SSDP `NOTIFY` advertisements announce the phone as a MediaServer until the camera completes the handshake.

All HTTP/SOAP/SSDP payloads are hand-built strings and responses are parsed with `substring`/regex rather than an XML parser, so exact header and body formatting matters to the camera. Keep those formats intact when refactoring. Cleartext HTTP is enabled in the manifest (`usesCleartextTraffic`) because the camera API is plain HTTP.
