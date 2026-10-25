# Passdroid++

Passdroid++ is an offline password manager for Android. It keeps your
passwords and notes in an encrypted database on your phone, protected by one
master password. It has no internet access, so nothing it holds can leave the
phone through the app.

Passdroid++ is based on Passdroid by Magnus Eriksson, whose last release was
2.3 (2012). It runs on Android 7.0 and later. See [Changes from Passdroid
2.3](#changes-from-passdroid-23) for what is new, and [Moving from the
original Passdroid](#moving-from-the-original-passdroid) if you used it.

## Features

### Entries

Each entry has a **system** (the name of the service, required),
**username**, **password**, **note** and **URL**. Every field is encrypted.

- The list is sorted by system and shows the username, unless "Display
  username" is off. Search matches the system.
- A long press on an entry in the list asks whether to copy its password.
- On the entry screen a tap on the password asks before it is copied. The
  URL opens in the browser.

### Master password and encryption

- The master password is turned into the encryption key with
  PBKDF2-HMAC-SHA256 and 600,000 iterations, which makes every guess slow for
  someone who has a copy of the data. Each field is encrypted with AES-256.
- A new master password needs at least 12 characters. The setup and change
  screens show how many are still missing. If an existing master password is
  shorter, a reminder with a "Change" button appears after each login with
  it.
- The master password can be changed in Settings. If it is forgotten, the
  data can't be recovered.

### Automatic logout

- After Passdroid++ has been in the background for **5 seconds**, unless
  "Log out on close" is off.
- While the system file picker is open for Passdroid++, you get at least 2
  minutes.
- Logging out forgets the key and everything that was decrypted.

### Clipboard

- Copied passwords are marked as sensitive, so Android 13 and later don't
  show them in the clipboard preview.
- With "Auto clear clipboard" (on by default) they are removed after about 45
  seconds, at the latest within a minute, and when the screen turns off.
- Where Android lets Passdroid++ read the clipboard, which since Android 10 is
  only while it is open, something you copied in another app in the meantime
  is left alone.

### Password generator

- Open it from the list menu, or from the add and edit screens to fill in the
  password.
- Letters and digits, letters only, digits only, or letters, digits and
  symbols, up to 99 characters. The length and the characters are
  remembered. The default is 10 letters and digits.
- A bar shows the strength.

### Import and export

- **Export** to a file you choose, encrypted (recommended) or unencrypted XML.
  An encrypted export needs the master password that was current when it was
  made, and a modified file is not imported.
- **Import** an encrypted file made with the current master password directly.
  For other encrypted files Passdroid++ asks for the master password they were
  made with. Exports of the original Passdroid can be imported.
- A file in a newer format, from a later version of Passdroid++, is refused
  until you update Passdroid++.
- **Importing replaces all entries** with those in the file.
- After importing an unencrypted file, Passdroid++ offers to delete it.

### Privacy

- **No internet permission.** Passdroid++ asks for no permissions at all.
- **Android backup is off**, so the database is not copied to the cloud.
  Encrypted exports are your backup, see [Backups](#backups).
- Screenshots and the preview in the recent apps are blocked on the screens
  that show your data.
- On the screens where you type secrets, the keyboard is asked not to learn
  from what you type, and autofill services are kept out.
- On Android 14 and later, passwords are hidden from accessibility services,
  except tools such as screen readers.

## Installing

- Android 7.0 or later.
- Passdroid++ is not on Google Play. Download `passdroid-<version>.apk` from
  the [releases](https://github.com/jurakovic/passdroid/releases) and open it
  on the phone. The first time, Android asks you to allow installing apps
  from the browser or the file manager.
- Releases marked as pre-release are previews of the next version.
- `checksums.txt` in each release has the SHA-256 checksum of the APK.
- [Obtainium](https://github.com/ImranR98/Obtainium) can watch the releases
  and install updates.
- The application id is `com.jurakovic.passdroid`, so Passdroid++ installs
  next to the original Passdroid and doesn't share its data.

## Moving from the original Passdroid

1. In the original app, go to Settings → Import / Export → Export to file,
   check "Encrypt the exported file" and export. It writes
   `passdroid_db.xml` to the phone's storage.
2. In Passdroid++, set up a new master password of at least 12 characters.
   Then go to Settings → Import / Export → Import from file, pick the file,
   and enter the master password of the original app when you are asked for
   it.
3. Check that all entries are there. Then delete the export file and
   uninstall the original app.

The original Passdroid derives its key from the master password in a very
weak way: anyone with a copy of its database or of an export can try
passwords extremely fast. Delete old copies wherever they are (cloud storage,
email), and if one may have leaked, don't reuse that master password. See
[INTERNALS.md](INTERNALS.md#legacy-formats).

## Backups

Android backup is off, so make an encrypted export now and then and keep it
somewhere safe, e.g. on your computer. An export opens with the master
password that was current when it was made, so make a new one after
changing the master password.

## Changes from Passdroid 2.3

Passdroid 2.3, the last release of the original Passdroid, is the baseline
of Passdroid++.

### Security

- **Key derivation:** PBKDF2-HMAC-SHA256 with 600,000 iterations and a random
  salt. Passdroid 2.3 used a single HMAC-SHA256 with a fixed key and no salt,
  so a copied database or export could be attacked very fast. Its exports
  can still be imported, and are then stored with the new key derivation.
- **Encrypted exports:** AES-256-GCM, which also detects changes to the file.
  2.3 used AES-CBC with a fixed IV and no authentication.
- Random values (IVs and salts) come from `SecureRandom` instead of
  `java.util.Random`, which is not secure.
- Master passwords need at least 12 characters. 2.3 accepted any, even an
  empty one.
- Every logout, also the automatic ones, wipes the key and the decrypted
  entries; 2.3 only did that for a logout from the menu.
- Copied passwords are removed from the clipboard after 45 seconds and are
  hidden in the clipboard preview. 2.3 only cleared the clipboard when the
  screen turned off.
- Android backup is off, the keyboard and autofill are kept out of what you
  type, and passwords are hidden from accessibility services.
- Imports read at most 10 MB and reject XML that could expand to huge
  amounts of text.
- Uses the standard crypto provider instead of Android's deprecated
  BouncyCastle one. The encryption is the same.

### Bugs fixed from 2.3

- A value containing `]]>` made the export impossible to import.
- Control characters in a value, e.g. from pasted text, made the export
  impossible to import.
- With a search active, a long press copied the password of another entry.
- "Log out on close" was ignored: the app always logged out after 5 seconds.
- Editing an entry could save it without a system name.
- An encrypted file that couldn't be imported for another reason, e.g. a
  newer file format, was reported as a wrong password.

### Updated for current Android

- Targets Android 17 (API 37) and builds with current Android tools. Android
  7.0 is still the minimum.
- Import and export go through the system file picker, without the storage
  permission, which newer Android versions no longer grant.
- No more crashes with the alarm and broadcast rules of Android 12 and 14.
  The back gesture and the edge-to-edge screens of newer Android versions
  are handled.
- The logout also works with the delayed broadcasts of Android 14, and a
  screen that Android restores after closing the app in the background
  doesn't show anything without a login.
- A new application id, so it installs next to the original.
- Unit tests, a GitHub workflow that checks every change, and one that
  publishes signed releases.

## Documentation

[INTERNALS.md](INTERNALS.md): building Passdroid++ from source, how it
works, the database and file formats, the security design with its threat
model, trade-offs and open items, and releasing.

## License

Passdroid++ is free software under the GNU General Public License, version 3
or later, see [LICENSE.TXT](LICENSE.TXT). Copyright (C) 2009-2019 Magnus
Eriksson, and the contributors of Passdroid.

The application icon is by VisualPharm.
