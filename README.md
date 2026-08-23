# Wren

A tiny bird on your wrist: an open-source, standalone email client for Wear OS.

Wren reads and triages your IMAP inbox directly on the watch — no companion phone
app required. It is built for small screens and small batteries: read a message,
archive it, delete it, flag it, move on.

**Status: early development.** The current build is a UI scaffold with placeholder
data. IMAP sync is the next milestone.

## Why

There is no properly-licensed open-source email client for Wear OS.
[Thunderbird for Android](https://github.com/thunderbird/thunderbird-android) has
an open request for a Wear OS port
([#6969](https://github.com/thunderbird/thunderbird-android/issues/6969)) that has
been unowned since 2023, and the closed-source watch mail apps come and go. Wren
aims to fill that gap, starting with the essentials.

## Planned v1 scope

- IMAP with app passwords (no OAuth in v1)
- Inbox list: sender, subject, time, unread state
- Plain-text message reading
- Triage: archive, delete, flag, mark read/unread
- Periodic background sync via WorkManager, offline cache via Room

Out of scope for v1: compose/reply, HTML rendering, attachments, push (IMAP IDLE),
OAuth.

## Target hardware

Primary test device is a Samsung Galaxy Watch 4 (Wear OS 6 / One UI 8 Watch,
450x450 round). Minimum supported is Wear OS 3 (`minSdk 30`).

## Building

Requires JDK 17+ and the Android SDK (platform 36).

```
./gradlew :app:assembleDebug
```

Install on a watch or Wear OS emulator over ADB:

```
adb install app/build/outputs/apk/debug/app-debug.apk
```

## Tech

- Kotlin, Compose for Wear OS Material 3 (`androidx.wear.compose.material3`)
- Single standalone Wear app module (`android.hardware.type.watch`,
  `com.google.android.wearable.standalone`)

## Credits

Wren is inspired by Mozilla Thunderbird and plans to reuse parts of the
Apache-2.0-licensed mail protocol code from
[thunderbird-android](https://github.com/thunderbird/thunderbird-android).
Wren is an independent project and is not affiliated with or endorsed by MZLA
Technologies or the Thunderbird project.

## License

[Apache-2.0](LICENSE)
