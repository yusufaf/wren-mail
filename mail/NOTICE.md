# Third-party code notice

The source code in this module is vendored from the
[Thunderbird for Android](https://github.com/thunderbird/thunderbird-android)
project (formerly K-9 Mail), specifically the `mail/common` and
`mail/protocols/imap` modules, plus small compatibility shims replacing the
upstream `core/common`, `legacy/logging`, and `feature/mail/folder/api`
dependencies (`net.thunderbird.*` packages).

Thunderbird for Android is licensed under the Apache License, Version 2.0.
The original license text is available in the repository root `LICENSE` file
and at <https://www.apache.org/licenses/LICENSE-2.0>.

This project is not affiliated with or endorsed by MZLA Technologies
Corporation or the Mozilla Foundation. "Thunderbird" is a trademark of the
Mozilla Foundation; it is used here only to credit the origin of the code.

Local modifications are limited to the compatibility shims noted above and the
following patches, each marked with a `Wren patch:` comment at the site:

- `com/fsck/k9/mail/store/imap/RealImapStore.kt` — `permanentFlagsIndex` is a
  `ConcurrentHashMap.newKeySet()` rather than a `mutableSetOf()` (it is written
  by `RealImapFolder` on every folder open), and `_combinedPrefix` is
  `@Volatile`. Wren's `MailService` runs IMAP operations concurrently on one
  store, which upstream's callers do not. `pathPrefix`, `pathDelimiter` and
  `combinedPrefix` remain unsynchronized while the first connection opens;
  `MailService` therefore runs the first successful operation on each store
  alone.

The vendored sources are otherwise unmodified from upstream.
