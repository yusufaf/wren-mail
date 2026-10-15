# Changelog

## 0.1.0 (2026-10-15)


### Features

* **ui:** accept and revoke certificate trust exceptions ([#18](https://github.com/yusufaf/wren-mail/issues/18)) ([1f3b071](https://github.com/yusufaf/wren-mail/commit/1f3b071844777171ab4f5681ca2490c7908ad660)), closes [#16](https://github.com/yusufaf/wren-mail/issues/16) [#17](https://github.com/yusufaf/wren-mail/issues/17)
* **ui:** explain an IP host that the certificate names only as its common name ([#28](https://github.com/yusufaf/wren-mail/issues/28)) ([5876834](https://github.com/yusufaf/wren-mail/commit/5876834f1ab4a89b6d49590f386971f774726f48)), closes [#25](https://github.com/yusufaf/wren-mail/issues/25)
* **ui:** explain why a server certificate failed validation in the trust prompt ([#20](https://github.com/yusufaf/wren-mail/issues/20)) ([a785691](https://github.com/yusufaf/wren-mail/commit/a785691210730d52a2f13224a5a56212ac9febd9)), closes [#19](https://github.com/yusufaf/wren-mail/issues/19)


### Bug Fixes

* **mailkit:** evict cached TLS context when trust exceptions change ([#15](https://github.com/yusufaf/wren-mail/issues/15)) ([fcce2f0](https://github.com/yusufaf/wren-mail/commit/fcce2f060d3df44db635fc39fff7448dc1eafad7)), closes [#13](https://github.com/yusufaf/wren-mail/issues/13)
* **mailkit:** scope the archive folder check and the store lock ([#14](https://github.com/yusufaf/wren-mail/issues/14)) ([2d07159](https://github.com/yusufaf/wren-mail/commit/2d07159eaa145f2ff4b5ab57f94ef9ea24ee9d41)), closes [#11](https://github.com/yusufaf/wren-mail/issues/11) [#12](https://github.com/yusufaf/wren-mail/issues/12)
* **security:** keep account data out of backups and device transfer ([#36](https://github.com/yusufaf/wren-mail/issues/36)) ([cee3178](https://github.com/yusufaf/wren-mail/commit/cee3178ca3bcd6e37b31b9c3f9e9ae8461edeec6))
* **ui:** ignore a fast second tap on the hostname-mismatch trust confirmation ([#26](https://github.com/yusufaf/wren-mail/issues/26)) ([b5f8f07](https://github.com/yusufaf/wren-mail/commit/b5f8f07d1c32c12089b2d9a497df08468dbf1b6c)), closes [#21](https://github.com/yusufaf/wren-mail/issues/21)


### Build

* add signed release workflow with release-please ([#34](https://github.com/yusufaf/wren-mail/issues/34)) ([b03b16e](https://github.com/yusufaf/wren-mail/commit/b03b16ea2be6d769d882d48b2c371ef28a44fb52))
