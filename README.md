# Wren

<!-- TODO(user): one-sentence pitch -->
A tiny bird on your wrist: an open-source, standalone email client for Wear OS.

Wren reads and triages your IMAP inbox directly on the watch. There is no phone
companion app: the watch talks to your mail server itself. It works with any
provider that offers IMAP with an app password, such as Fastmail, iCloud Mail,
Gmail or a self-hosted server. It is built for small screens and small
batteries: read a message, archive it, delete it, flag it, move on.

**Status:** usable, not yet released. It runs on a Galaxy Watch 4 against a real
Gmail account. What works today:

- Account setup on the watch: server, port, SSL/TLS or STARTTLS, username and
  app password, with a prompt to trust a self-signed or mismatched certificate
- Live inbox: sender, subject, time and unread state, cached with Room and
  synced every 15 minutes with WorkManager
- Reading messages as plain text (HTML-only mail is converted to text)
- Archive, delete, flag and mark read/unread, from the message or by swiping or
  long-pressing an inbox row. Actions that fail offline are queued and retried.
- A minified release build

## Why

There is no other open-source email client that runs standalone on Wear OS, as
far as this project knows, and the closed-source watch mail apps come and go.
[Thunderbird for Android](https://github.com/thunderbird/thunderbird-android) has
an open request for a Wear OS port
([#6969](https://github.com/thunderbird/thunderbird-android/issues/6969)) from
2023, with no assignee. In October 2026 a contributor posted a phone-first
prototype from a fork there, in which Thunderbird on the phone syncs and the
watch shows what the phone sends it. Wren takes the standalone route instead,
so nothing needs to be installed on the phone.

## Accounts

Wren signs in with an IMAP username and an app password, entered on the watch.

- Fastmail: `imap.fastmail.com`, [app password](https://www.fastmail.help/hc/en-us/articles/360058752854)
- iCloud Mail: `imap.mail.me.com`, [app-specific password](https://support.apple.com/en-us/102654)
- Gmail: `imap.gmail.com`, [app password](https://support.google.com/accounts/answer/185833)
  (needs 2-Step Verification)
- Self-hosted servers, including ones with a self-signed certificate

**Not supported:** Outlook.com, Hotmail and Live addresses, and Microsoft 365
work or school accounts. Microsoft only accepts OAuth for IMAP on these
([Outlook.com since September 2024](https://support.microsoft.com/en-us/topic/c5d65390-9676-4763-b41f-d7986499a90d),
[Microsoft 365 since October 2022](https://learn.microsoft.com/en-us/exchange/clients-and-mobile-in-exchange-online/deprecation-of-basic-authentication-exchange-online)),
and Wren has no OAuth yet.

## Not in v1

These are left out on purpose for the first release:

- Compose and reply
- OAuth sign-in (app passwords only)
- HTML rendering and attachments
- Push (IMAP IDLE): new mail arrives with the 15-minute sync
- Folders other than the inbox. Archive moves mail to a folder named `Archive`,
  which Wren creates if it is missing.
- Offline reading: message bodies aren't cached, so opening one needs a
  connection

## Install

There is no release yet. The first signed APK will be published on the
[Releases page](https://github.com/yusufaf/wren-mail/releases). Until then you
can build it yourself (see [Building](#building)).

Wren isn't on the Play Store, so the APK has to be sideloaded onto the watch.
You don't need a computer for that: an app on your Android phone can install it
over the watch's Wireless debugging.

1. Connect the watch and the phone to the same Wi-Fi network.
2. On the watch, turn on Developer options by tapping the build number until
   it says you're a developer (Settings > System > About > Versions > Build
   number on most watches, or Settings > About watch > Software information >
   Software version on a Galaxy Watch). Then turn on **ADB debugging** and
   **Wireless debugging** in Settings > Developer options.
3. On the phone, install [Wear Installer 2](https://play.google.com/store/apps/details?id=org.freepoc.wearinstaller2)
   or [Bugjaeger](https://play.google.com/store/apps/details?id=eu.sisik.hackendebug),
   and download `wren-<version>.apk` from the Releases page.
4. Pair the app with the watch: on the watch, open Wireless debugging > Pair new
   device, and enter the pairing code and port it shows into the app.
5. Install the APK from the app, and allow debugging on the watch if it asks.
6. Turn Wireless debugging off again when you're done; it uses battery.

Both apps are third-party tools and not affiliated with Wren.

With a computer, use `adb` instead:

```
adb pair <watch-ip>:<pairing-port>
adb connect <watch-ip>:<port>
adb install wren-<version>.apk
```

Later releases install over the previous one and keep your account. A build
you made yourself is signed with a different key, so uninstall it before
installing a release.

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
- Room for the inbox cache, WorkManager for background sync

## Credits

Wren is inspired by Mozilla Thunderbird. Its `mail` module is the
Apache-2.0-licensed IMAP code from
[thunderbird-android](https://github.com/thunderbird/thunderbird-android),
vendored with small patches listed in [`mail/NOTICE.md`](mail/NOTICE.md).
Wren is an independent project and is not affiliated with or endorsed by MZLA
Technologies or the Thunderbird project.

## License

[Apache-2.0](LICENSE)
