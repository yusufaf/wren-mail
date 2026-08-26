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

Local modifications are limited to the compatibility shims noted above; the
vendored sources are otherwise unmodified from upstream.
