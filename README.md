# Wren

Wren is an open-source, standalone email client for Wear OS: read and triage an
IMAP inbox (Fastmail, iCloud Mail, Gmail or a self-hosted server, signed in with
an app password) right on your watch, with no phone companion app.

It is built for small screens and small batteries: read a message, archive it,
delete it, flag it, move on.

**Status:** early but usable. Reading the inbox and opening messages
are tested on a Galaxy Watch 4 against a real Gmail account. What works today:

- Account setup on the watch: server, port, SSL/TLS or STARTTLS, username and
  app password, with a prompt to trust a self-signed or mismatched certificate
- Live inbox: sender, subject, time and unread state, cached with Room. A
  background sync runs about every 15 minutes (WorkManager's minimum interval)
  while the watch has a network connection, and Android may delay it further
  in Doze or battery saver. Launching Wren, or tapping Refresh in the inbox,
  syncs right away.
- Reading messages as plain text (HTML-only mail is converted to text)
- Archive, delete, flag and mark read/unread, from the message or by swiping or
  long-pressing an inbox row. Actions that fail offline are queued and retried.
- A minified, signed release build

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
  (needs 2-Step Verification). Archive and delete behave differently on Gmail,
  and neither has been tested end to end on Gmail yet:
  - Delete marks the message deleted and expunges it; it doesn't move it to
    Trash. With Gmail's default IMAP settings (Auto-Expunge on, "Archive the
    message"), Gmail archives the message instead of trashing it. To send it
    to Trash, change that setting in Gmail's Forwarding and POP/IMAP settings.
  - Archive moves the message to the folder the server marks as its archive.
    On Gmail that is All Mail, so the message leaves the inbox the way Gmail's
    own Archive button does. If the server marks no archive folder, Wren uses a
    folder named `Archive` and creates it when it is missing.
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
- Push (IMAP IDLE): new mail arrives with the next background sync
- Folders other than the inbox. Archive moves mail to the server's archive
  folder (see above).
- Older mail: the inbox shows only the 50 most recent messages
- Offline reading: message bodies aren't cached, so opening one needs a
  connection

## Install

Signed APKs are published on the
[Releases page](https://github.com/yusufaf/wren-mail/releases). You can also
build it yourself (see [Building](#building)).

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
   and download the `.apk` attached to the latest release on the Releases
   page.
4. Pair the app with the watch: on the watch, open Wireless debugging > Pair new
   device, and enter the pairing code and port it shows into the app.
5. Install the APK from the app, and allow debugging on the watch if it asks.
6. Turn Wireless debugging off again when you're done; it uses battery.

Both apps are third-party tools and not affiliated with Wren.

With a computer, use `adb` instead:

```
adb pair <watch-ip>:<pairing-port>
adb connect <watch-ip>:<port>
adb install <downloaded>.apk
```

Releases are signed with one release key, so updates install in place. An APK
you build yourself is signed with a different key, so uninstall it before
installing a release. Uninstalling erases Wren's data on the watch: the saved
account and app password, and any trusted certificates.

### Verify the download

Each release has `wren-<tag>.apk`, a `wren-<tag>.apk.sha256` checksum and the
R8 `wren-<tag>-mapping.txt`, where `<tag>` includes the leading `v`, for example
`wren-v0.1.0.apk`.

```
sha256sum -c wren-<tag>.apk.sha256
gh attestation verify wren-<tag>.apk -R yusufaf/wren-mail
```

The checksum only detects a corrupted download: anyone who can replace the APK
can replace the checksum file too. The attestation is what ties the APK to the
workflow in this repository that built it. It needs the
[GitHub CLI](https://cli.github.com/) 2.49 or newer, and `-R` must name the
repository the release came from.

To check the signing key, `apksigner verify --print-certs wren-<tag>.apk`
(from the Android SDK build-tools) should print this certificate SHA-256 digest:

<!-- Keep in sync with the RELEASE_CERT_SHA256 repository variable, which the
     release workflow checks the APK against. -->

```
ac1480ad30581ce2232b71ce75fad0922a038bbae32f3b4a26156e35c0c3aab8
```

As colon-separated bytes, the form most verification apps (AppVerifier,
Obtainium) show:

```
AC:14:80:AD:30:58:1C:E2:23:2B:71:CE:75:FA:D0:92:2A:03:8B:BA:E3:2F:3B:4A:26:15:6E:35:C0:C3:AA:B8
```

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
