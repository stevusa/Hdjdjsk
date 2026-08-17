# AirBox Receiver

Android TV / TV box receiver inspired by apps such as AirScreen.

## Current MVP

- **DLNA / UPnP MediaRenderer discovery** via SSDP.
- Minimal **AVTransport** handling for `SetAVTransportURI`, `Play`, and `Stop`.
- Built-in **HTTP receiver API** on port `8080` (`/play`, `/stop`, `/status`).
- Full-screen playback through Android `VideoView` / `MediaPlayer`.
- TV-friendly launcher UI and background receiver service.
- GitHub Actions workflow that builds a debug APK in the cloud.

## Protocol scope

AirPlay/RAOP, Google Cast, and Miracast are represented as planned protocol adapters, but are **not falsely advertised as fully compatible** in this MVP. Full compatibility requires substantial protocol-specific work; some parts depend on proprietary/certified components or Android system/vendor capabilities.

## Build

The project uses Android Gradle Plugin 9.3.0, Gradle 9.5.0 and JDK 17.

On GitHub, open **Actions → Build AirBox Receiver APK → Run workflow**. When the build finishes, download the `AirBoxReceiver-debug-apk` artifact.

Local build (with Gradle 9.5 and Android SDK installed):

```bash
gradle :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## HTTP API examples

Play a direct media URL:

```text
http://TV_BOX_IP:8080/play?url=https%3A%2F%2Fexample.com%2Fvideo.mp4
```

Stop playback:

```text
http://TV_BOX_IP:8080/stop
```

Check receiver status:

```text
http://TV_BOX_IP:8080/status
```

## Package

`rs.stevusa.airboxreceiver`
