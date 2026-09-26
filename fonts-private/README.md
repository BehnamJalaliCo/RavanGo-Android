# Private brand font (Ravagh)

`ravagh-fonts.tar.gz.gpg` is the **encrypted** RavanGo brand font *Ravagh* (fontiran.com, license #136710).
The font is commercial and personalised to the licensee; it must never be committed in plain form to this public
repository. It is decrypted only inside CI (or locally) and embedded into the app binary as permitted by the licence.

Decrypt locally (needs the passphrase stored in the `RAVAGH_FONT_PASSPHRASE` repository secret):

```bash
mkdir -p private/fonts/ravagh
gpg --batch --pinentry-mode loopback --passphrase "$RAVAGH_FONT_PASSPHRASE" \
    -d fonts-private/ravagh-fonts.tar.gz.gpg | tar -xz -C private/fonts/ravagh
```

`private/` is git-ignored. Without the files the build falls back to the OFL font Vazirmatn automatically.
