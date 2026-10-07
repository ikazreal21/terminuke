# Manual device checklist

Run on an Android 14+ device after installing the debug APK.

- [ ] Add, edit, search, connect, and delete a saved host.
- [ ] Connect to a disposable OpenSSH server with password authentication.
- [ ] Verify the first connection shows a SHA256 host-key fingerprint; test Trust Once, Trust Always, and Reject.
- [ ] Change the test server host key and verify the app refuses to connect without silently replacing the saved key.
- [ ] Generate an Ed25519 key, attach it to a host, and connect with public-key authentication.
- [ ] Import an unencrypted PEM key and connect; test an encrypted key/passphrase.
- [ ] Use `ls`, `vim`, `htop`, Ctrl-C, arrows, Esc, Tab, selection, copy, and paste.
- [ ] Test screen rotation, Gboard input, and an attached physical keyboard.
- [ ] Turn the screen off briefly and verify the foreground-session notification remains visible.
- [ ] Enable screenshot protection and verify Recents/screenshots redact the terminal.
- [ ] Export hosts, inspect the JSON to confirm no password/private key data, then import it.
- [ ] Confirm Android's automatic app backup is disabled.
