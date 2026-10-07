# Third-party notices

## Termux terminal emulator and terminal view

The contents of `terminal-emulator/` and `terminal-view/` are derived from the Termux application repository, commit `8629e632fcb95da272221be327db653fb24befe9` (upstream modules report version `0.118.0`). The Termux application's overall repository is GPL-3.0-only, but its terminal emulator and terminal view modules are explicitly excepted and released under the Apache License 2.0. See the upstream `LICENSE.md` and the Apache 2.0 text in `LICENSE-2.0.txt`.

Local modifications: `TerminalSession.java` adds a remote-stream initialization path so the emulator can attach to SSH streams without spawning a device-local shell; `TerminalBitmap.java` uses numeric API level 35 to remain source-compatible with compileSdk 34. All other source is preserved from the upstream commit.

Upstream: <https://github.com/termux/termux-app>
