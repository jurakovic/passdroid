# Passdroid++ internals

How Passdroid++ is built and how it works: the structure of the code, the
session and logout handling, the key derivation and encryption, the database
and file formats, the security design, testing, making changes and
releasing.

## Contents

- [Overview](#overview)
- [Building](#building)
- [Source layout](#source-layout)
- [Startup and navigation](#startup-and-navigation)
- [Session and automatic logout](#session-and-automatic-logout)
- [Master key](#master-key)
- [Database](#database)
- [Entry encryption](#entry-encryption)
- [Loading entries](#loading-entries)
- [Screens and data flow](#screens-and-data-flow)
- [Clipboard](#clipboard)
- [Export and import](#export-and-import)
- [Password generator](#password-generator)
- [Settings](#settings)
- [Android platform details](#android-platform-details)
- [Security](#security)
  - [Threat model](#threat-model)
  - [Design](#design)
  - [Decisions](#decisions)
  - [Open items](#open-items)
  - [Recommendations](#recommendations)
  - [Sources](#sources)
- [Tests](#tests)
- [Making changes](#making-changes)
- [Releasing](#releasing)
- [Known limitations](#known-limitations)

## Overview

- A single-module Android app in **Java**, on the plain platform classes
  (`Activity`, `ListActivity`, `PreferenceActivity`, `SQLiteOpenHelper`). No
  AndroidX, no dependency injection, no Kotlin.
- `minSdk` 24 (Android 7.0), `compileSdk` and `targetSdk` 37 (Android 17).
- No dependencies. Tests: JUnit 4 and BouncyCastle.
- Java package and namespace `com.kodholken.passdroid`, the package of the
  original Passdroid. The application id is `com.jurakovic.passdroid`: the
  original id is signed by its author, so an app with it can't be installed
  next to the original or published under it.
- The app has no permissions. There is no `INTERNET` permission, so
  everything stays on the device unless the user exports it.

## Building

You need JDK 21 and the Android SDK with platform 37 and build tools 36.0.0.
Point `ANDROID_HOME` or `sdk.dir` in `local.properties` to the SDK.

```
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug
```

- Gradle 9.8.2 (wrapper) and the Android Gradle plugin 9.4.1.
- **The version is supplied at build time**, with `-PversionCode=42
  -PversionName=3.1.0`, or with the environment variables
  `ORG_GRADLE_PROJECT_versionCode` and `ORG_GRADLE_PROJECT_versionName`.
  Local builds have version code 1 and version name `0.0.0-dev`.
  `app/build.gradle` checks both:
  - `versionName` must be a [semantic version](https://semver.org) (semver
    2.0.0), e.g. `3.1.0`, `3.1.0-preview.1` or `3.1.0+build.5`. It is only
    shown (About screen) and written to exports as `app-version`. Nothing
    compares it: the database has its own data version and exports their
    own format version, see [Database](#database) and [XML](#xml).
  - `versionCode` must be a whole number from 1 to 2,100,000,000 (the Google
    Play limit). Android doesn't install an update with a lower one.
- `unitTests.returnDefaultValues = true`, since `FileImporter` logs through
  `android.util.Log`, which is a stub in local unit tests.

The "Build" workflow, `.github/workflows/build.yml`, runs on every push and
pull request. It installs platform `android-37.0` and build tools 36.0.0,
runs `testDebugUnitTest lintDebug assembleDebug assembleRelease` with the
default version, and uploads the debug APK. The debug APK is signed with a
debug key generated for each run, so the debug APKs of different runs can't
be installed over each other. Releases are made with the "Publish" workflow,
see [Releasing](#releasing).

## Source layout

All code is in `app/src/main/java/com/kodholken/passdroid/`.

| Area | Classes |
|---|---|
| Startup | `PassdroidApplication`, `MainActivity`, `InitializeActivity`, `PasswordLengthHint`, `LoginActivity` |
| Session | `Session`, the four `Timeout*Activity` base classes, `ScreenOffListener` |
| Crypto | `MasterKey` (key derivation), `Crypto` (legacy key, AES helpers), `PasswordEntry` (field encryption), `Base64` |
| Data | `db/PasswordData`, `db/SystemData`, `db/DbMigration`, `PasswordModel`, `PasswordModelListener` |
| Entries | `PasswordListActivity`, `PasswordAdapter`, `FullWidthLayout`, `ShowActivity`, `AddActivity`, `EditActivity` |
| Clipboard | `Utils.copyPasswordToClipboard()`, `ScreenOffListener` |
| Export and import | `ImportExportActivity`, `FileExportActivity`, `FileExporter`, `FileImportActivity`, `FileImporter`, `FileImporterException`, `ImportPasswordActivity` |
| Generator | `GeneratePasswordActivity`, `DisplayPasswordActivity` |
| Settings | `SettingsActivity`, `res/xml/settings.xml`, `ChangePasswordActivity` |
| Misc | `Utils`, `Version`, `Constants`, `AboutActivity`, `OverscrollListView` |

Resources worth knowing: `res/layout-land/` (landscape layouts of the About
screen and the generator), `res/values/arrays.xml` (generator character
sets).

Unit tests are in `app/src/test/java/com/kodholken/passdroid/`, see
[Tests](#tests).

## Startup and navigation

1. `PassdroidApplication.onCreate()` registers the `ScreenOffListener`.
   This runs whenever the process starts, also when Android restarts the
   process for the activity that was on top, which doesn't go through
   `MainActivity`.
2. `MainActivity` (the launcher activity) decides where to go in
   `onResume()`:
   - logged in: the password list;
   - otherwise it makes sure the `system` table exists, runs
     `DbMigration.preLoginMigration()`, which stores the data version if
     there is none (see [Database](#database)), and starts `LoginActivity`
     if a master key record exists, else `InitializeActivity`.
3. `InitializeActivity` creates the master key from the new password (at
   least 12 characters), stores the record and logs in.
4. `LoginActivity` derives the key in a background thread, runs the data
   migrations (`DbMigration.postLoginMigration()`), upgrades a legacy master
   key (see [Master key](#master-key)), and starts `PasswordListActivity`. It
   stays below the list, so a logout returns to it.

Back handling: back on the login screen exits the app. It sets
`Session.setExitMain(true)` so that `MainActivity` finishes itself instead of
starting the login again. Back on the password list closes the search, or
else asks whether to log out. On Android 13 and later back is handled with
an `OnBackInvokedCallback`, since Android 16 doesn't deliver
`onBackPressed()` to apps that target it.

## Session and automatic logout

`Session` is a process-wide singleton with the key, the login state, a
"reload the list" flag and the timeout state. Nothing of it is persisted:
when the process dies, the user is logged out.

### Logging out

`Session.setLoggedIn(false)` is the single place where a logout happens,
whatever the reason. It zeroes the key array, drops the reference, and calls
`PasswordModel.clear()` to drop the decrypted entries.

Activities that show data extend one of four base classes:
`TimeoutActivity`, `TimeoutListActivity`, `TimeoutPreferenceActivity` and
`SherlockTimeoutActivity`. They are copies of the same code on different
platform base classes, and `SherlockTimeoutActivity` is identical to
`TimeoutActivity`. Each:

- sets `FLAG_SECURE` on its window,
- registers a not exported receiver for the timeout broadcast, which logs
  out and finishes the activity,
- keeps its content clear of the system bars (`Utils.applySystemBarInsets()`),
- counts resumes and pauses in the session. When the count drops to 0, the
  app has gone to the background (or to another app's activity) and
  `Session.setTimeoutTimer()` runs. `onResume()` runs
  `Session.clearTimeoutTimer()` and finishes the activity if the session is
  no longer logged in.

### The timeout

- `setTimeoutTimer()`, if "Log out on close" is on, sets an inexact
  `ELAPSED_REALTIME` alarm for 5 seconds. The alarm sends the timeout
  broadcast, as an immutable `PendingIntent` limited to the app's package.
- `clearTimeoutTimer()` cancels the alarm and logs out **if the deadline has
  passed**. The alarm is inexact, and since Android 14 broadcasts are held
  back while the app is cached, so the check on return is what actually
  enforces the timeout.
- While the system file picker is open for the app,
  `setExtendedTimeout(true)` makes the delay 2 minutes. The activity that
  started the picker calls `clearTimeoutTimer()` itself in
  `onActivityResult()`, which runs before `onResume()`, and finishes if the
  session ended.
- A login clears a timer left from before it, so the login isn't undone
  right away.

### Background work

`Utils.runWithProgress()` runs slow work (key derivation, an import with a
password) in a plain thread behind a non-cancelable progress dialog, and the
completion on the UI thread, unless the activity has been destroyed or is
finishing. Screens guard against starting the same work twice with a flag.

## Master key

### Current format

`MasterKey` derives a 256 bit key with **PBKDF2-HMAC-SHA256**, 600,000
iterations and a random 16 byte salt. The `system` table stores the record

```
pbkdf2-sha256$<iterations>$<base64 salt>$<base64 verifier>
```

with `verifier = SHA-256("passdroid-key-verifier" || key)`. A password is
checked by deriving the key with the stored salt and iteration count and
comparing the verifier in constant time (`MessageDigest.isEqual`).

- `deriveKey()` uses the platform's `PBKDF2WithHmacSHA256`. On Android 7.0
  and 7.1, which don't have it, and for an empty password, which the platform
  rejects, it falls back on a pure Java implementation of RFC 8018
  (`pbkdf2HmacSha256()`), tested against the RFC 7914 test vectors. Both
  encode the password as UTF-8.
- Iteration counts read from files are capped at `MAX_ITERATIONS`
  (10,000,000).
- `MIN_PASSWORD_LENGTH` (12) is counted in code points. It only applies to
  new master passwords; a login with a shorter one passes
  `EXTRA_SHORT_PASSWORD` to the list, which shows a reminder. On the setup
  and change screens `PasswordLengthHint` shows the minimum below the field
  and counts the missing characters while typing. There is no strength
  meter: the generator's assumes random characters, and a typed password
  like `Password1234!` would look strong.

### Legacy format

The original Passdroid derives the key without a salt or iterations:

- The key is `HMAC-SHA256(key = "notverysecretiv", password)`
  (`Crypto.hmacFromPassword()`).
- The record is the base64 of 32 bytes that, XORed with that HMAC, give 28
  random bytes followed by their CRC32 (`Crypto.verifyPassword()`). So a
  guess costs one HMAC and a CRC.
- `MasterKey.isLegacy()` recognizes it by the missing `pbkdf2-sha256$`
  prefix.

At a successful login with a legacy record, `LoginActivity` also creates a
new PBKDF2 key from the password and calls `DbMigration.changeKey()`, which
re-encrypts all entries and stores the new record in one transaction. If
that fails, the legacy key stays in use and the upgrade is tried again at
the next login.

### Changing the master password

`ChangePasswordActivity` derives the key from the old password (which
verifies it) and a new key from the new password in a background thread,
then calls `DbMigration.changeKey()`. On success the old session key is
zeroed and replaced.

`DbMigration.changeKey()` decrypts every entry with the old key and only
continues if every field decrypted. It then re-encrypts all entries and
updates the `key` record, all in one SQLite transaction. It runs on the UI
thread.

## Database

One SQLite database, `password.db` (`Constants.DBNAME`), in the app's private
storage. Schema version 2 (`Constants.DBVERSION`).

```sql
CREATE TABLE data (id INTEGER PRIMARY KEY AUTOINCREMENT,
                   system TEXT NOT NULL, username TEXT, password TEXT,
                   note TEXT, url TEXT);

CREATE TABLE system (id INTEGER PRIMARY KEY AUTOINCREMENT,
                     attribute TEXT NOT NULL, value TEXT);
```

Every value in `data` except `id` is encrypted, see
[Entry encryption](#entry-encryption). `system` holds attributes:

| Attribute | Value |
|---|---|
| `key` | The master key record |
| `data_version` | The data version, `DbMigration.DATA_VERSION` (3) |
| `version` | An app version, e.g. `2.4`, which older databases have instead of `data_version`. Replaced by `data_version` at the next start |

Two `SQLiteOpenHelper` classes, `PasswordData` and `SystemData`, open the same
database. Either can trigger the upgrade, so both `onUpgrade()` methods only
call `DbMigration.handleDatabaseUpgrade()`. Each table is created on demand
by its helper's `verifyTable()`.

There are two kinds of migration:

- **Schema migrations** by database version, in
  `DbMigration.handleDatabaseUpgrade()`: 1 → 2 adds `note` and `url`.
- **Data migrations** by data version, in
  `DbMigration.postLoginMigration()`, run after the login because they need
  the key. `data_version` is only updated when they succeed.
  - 1 (Passdroid 0.9, AES-ECB) → 3,
  - 2 (Passdroid 0.95 to 1.x, AES-CBC with a zero IV) → 3: AES-CBC with a
    random IV, used since Passdroid 2.0.

  Before the login, `DbMigration.preLoginMigration()` stores the data
  version: 3 in a new database, or the one that the stored app version
  implies (`getLegacyDataVersion()`: up to 0.9 is 1, below 2.0 is 2, else 3).
  An app version it can't parse counts as 3, since converting current
  entries would destroy them. No database of the Passdroid++ application id
  has an app version before 2.3, so in practice no conversion runs.

  The PBKDF2 upgrade of a legacy key is not one of these; it is decided by
  the record format.

## Entry encryption

Each field is encrypted separately (`PasswordEntry.encrypt()`):

```
base64( IV (16 random bytes) || AES-256-CBC-PKCS7(key, IV, UTF-8 value) )
```

- `"AES/CBC/PKCS5Padding"` from the default provider. For AES this is the
  same PKCS #7 padding as the BouncyCastle `"PKCS7Padding"` that wrote older
  entries; the tests decrypt data written by BouncyCastle and vice versa.
- `NULL` (a field the entry never had) and an empty string are different:
  `null` stays `null`, `""` is encrypted.
- Not authenticated: someone who can write the database could swap or
  corrupt values. See [Decisions](#decisions).
- Legacy formats, only read for the migrations above: AES-ECB, and CBC with
  a zero IV; both with 2 random bytes in front of the value.

`decrypt()` returns `null` when a value can't be decrypted.
`DbMigration.changeKey()` uses that to refuse re-encrypting a database with
the wrong key.

## Loading entries

`PasswordModel` is a process-wide singleton over the `data` table.

- `getPasswords()` loads all rows on first use and decrypts **all fields**.
  The entries are sorted by system (case-insensitive), then username.
- `setPasswords()` replaces **all** rows in one transaction, for an import.
  It encrypts the entries with the session key, and fails if the user has
  been logged out.
- `clear()` drops everything; `Session` calls it at every logout.
- Listeners (`PasswordModelListener`, i.e. the list screen) are notified
  when the entries change. The list removes itself in `onDestroy()`, since
  the model outlives it.

Screens that change entries write to the database directly and set
`Session.setNeedReload(true)`, which makes the list reload in `onResume()`.

## Screens and data flow

Screens pass the decrypted values of an entry in intent extras.

- **`PasswordListActivity`**: `PasswordAdapter` filters by a lowercase
  substring of the system. The search field is an action view in the action
  bar (`collapsable_search.xml`). The action bar measures action views as
  `wrap_content`, so its root, `FullWidthLayout`, takes the whole width it is
  offered. A long press asks whether to copy the password. The entry is
  taken from the adapter, not the model, so the right one is used while the
  list is filtered.
- **`ShowActivity`**: shows the values it gets in the intent. A tap on the
  password asks whether to copy it.
- **`EditActivity`**: gets the values in the intent, encrypts all fields on
  save and updates the row. The system name is required, as on
  `AddActivity`.
- **`AddActivity`**: inserts a new row.

## Clipboard

- Passwords go through `Utils.copyPasswordToClipboard()`: the clip gets the
  label `"password"` and the `android.content.extra.IS_SENSITIVE` extra,
  which hides it in the clipboard preview of Android 13 and later, and
  `Session.setPasswordInClipboard(true)` is set.
- `ScreenOffListener` receives `SCREEN_OFF`. It is registered once per
  process on the application context (a manifest receiver can't get this
  broadcast). If a password was copied and "Auto clear clipboard" is on, it
  clears the clipboard with `clearPrimaryClip()` (Android 9 and later) or an
  empty clip. It doesn't check what the clipboard holds, since Android 10
  doesn't let apps read it in the background.

## Export and import

### XML

Both cleartext exports and the content of encrypted exports are this XML,
UTF-8 encoded:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<passdroid version="3" app-version="0.0.0-dev">
  <system name="GitHub">
    <username><![CDATA[alice]]></username>
    <password><![CDATA[s3cret]]></password>
    <note><![CDATA[Recovery codes in the safe]]></note>
    <url><![CDATA[https://github.com]]></url>
  </system>
</passdroid>
```

- The system name is an attribute, XML escaped. Other values are in CDATA
  sections. A `]]>` inside a value is split across two sections
  (`]]]]><![CDATA[>`), and the importer joins them with `getTextContent()`.
- Characters XML 1.0 doesn't allow, even in CDATA (most control characters,
  lone surrogates, U+FFFE/U+FFFF), are removed
  (`FileExporter.removeInvalidChars()`).
- Fields that are `null` are left out. A file without some of the fields
  imports them as empty values.
- The `version` attribute is the file format version,
  `FileExporter.FORMAT_VERSION` (3). `app-version` is the version name of
  the app that wrote it, for information only. Older files have an app
  version in `version` instead, which `FileImporter.getFormatVersion()` maps
  to a format:

  | Format | `version` | |
  |---|---|---|
  | 1 | `1.6` and earlier | Unescaped system names |
  | 2 | `1.7` to `2.3` | |
  | 3 | `2.4`, or `3` | CDATA values |

  A newer format, or an app version after 2.4, is refused: an older app
  would leave out what it doesn't know. Files in format 1 go through a
  line-based pass that escapes the system names first
  (`createInputStream()`). The version is read with a regular expression
  before parsing, since those files aren't well-formed.

### Encrypted export ("sq2")

```
offset  size  content
0       3     "sq2"
3       4     PBKDF2 iteration count, big endian
7       1     salt length n
8       n     PBKDF2 salt
8+n     12    AES-GCM nonce (random)
20+n    ...   AES-256-GCM(XML), followed by the 16 byte tag
```

- The key is the database key, so the salt and iteration count of the
  master key record are written into the header. The header, the bytes
  before the nonce, is the GCM additional data, so it can't be changed
  without the import failing.
- An export is therefore tied to the master password that was current when
  it was made. After a password change, older exports still need the old
  password.

### Legacy encrypted export ("sqt")

`"sqt"` followed by AES-256-CBC with a zero IV over 2 random bytes and the
XML, with the legacy key. The original Passdroid writes these. Passdroid++
only writes one for a database whose legacy key couldn't be upgraded, and
always reads them.

### Exporting

`FileExportActivity` asks for a file with `ACTION_CREATE_DOCUMENT` (the
Storage Access Framework, no storage permission), opens it with mode `"wt"`
so an existing file is truncated, and writes the XML of the entries of
`PasswordModel`, encrypted or not. The stream is closed before success is
reported, since some document providers only store the data then.

### Importing

`FileImportActivity`:

1. picks a file with `ACTION_OPEN_DOCUMENT` and reads it into memory, at
   most 10 MB;
2. detects the format by the first bytes (`sq2`, `sqt`, else XML);
3. **encrypted**: tries the current session key first. If the key is
   wrong, `ImportPasswordActivity` asks for the password, and
   `parseEncryptedWithPassword()` derives the key: PBKDF2 with the salt and
   iterations from the header for `sq2`, the legacy HMAC for `sqt`.
   `FileImporterException.isWrongKey()` tells whether another password could
   help. An `sq2` file that decrypts is authenticated, so a later error,
   such as a newer format, is an error of the file and is shown as such.
   `sqt` is not authenticated, so all its errors count as a wrong key;
4. **cleartext**: parsed directly; afterwards the app offers to delete the
   file (`DocumentsContract.deleteDocument()`);
5. stores the entries with `PasswordModel.setPasswords()`, which **replaces
   all entries**.

## Password generator

- `GeneratePasswordActivity` picks every character uniformly with
  `SecureRandom` from one of four character sets: letters and digits (62),
  letters (52), digits (10), or letters, digits and `!#@%&/()?+-_:;*=$`
  (79). The length is 1 to 99, 10 by default. The length and the set are
  stored in the preferences `passwordLength` and `passwordCharset` when a
  password is generated.
- **Strength**: a bar for length × log₂(set size) bits, full at 80 bits.
- Opened from the add or edit screen, the result is handed back through the
  static `getGeneratedPassword()`. Opened from the list, it is shown on
  `DisplayPasswordActivity`, where a tap asks whether to copy it.

## Settings

Settings are default `SharedPreferences`, defined in `res/xml/settings.xml`:

| Key | Default | Used by |
|---|---|---|
| `close_logout` | `true` | `Session.setTimeoutTimer()` |
| `display_username` | `true` | the password list |
| `clear_clipboard` | `true` | `ScreenOffListener` |
| `passwordLength`, `passwordCharset` | 10, 0 | the generator |

## Android platform details

Behavior that depends on the Android version, and where it is handled:

| Android | What | Where |
|---|---|---|
| 7.0–7.1 | No platform PBKDF2-HMAC-SHA256 | `MasterKey.deriveKey()` fallback |
| 9 | `clearPrimaryClip()` | `ScreenOffListener` |
| 10 | No clipboard reads in the background | `ScreenOffListener` |
| 12 | Immutable `PendingIntent`s, exported launcher activity | `Session`, manifest |
| 13 | `RECEIVER_NOT_EXPORTED`, sensitive clips, back callbacks | `Utils.registerNotExportedReceiver()`, `Utils`, activities |
| 14 | Held-back broadcasts for cached apps | `Session.clearTimeoutTimer()` |
| 15 | Edge-to-edge windows | `Utils.applySystemBarInsets()` |
| 16 | No `onBackPressed()` for apps that target it | `OnBackInvokedCallback` |

Only `MainActivity` is exported. The timeout receivers are registered at
runtime as not exported.

## Security

How Passdroid++ protects its data, the trade-offs that were made on purpose,
and what is still open. The sections above describe how each part works.

### Threat model

Passdroid++ protects passwords **at rest**:

- a lost or stolen phone,
- a copy of the app's database,
- a backup or an export file that ends up somewhere else.

It has **no network access** (no `INTERNET` permission), so nothing it holds
can be sent anywhere by the app itself.

Out of scope, as for any app on Android:

- someone who has the phone while Passdroid++ is unlocked,
- malware with root or a kernel exploit, which can read the app's memory or
  record the master password as it is typed.

Everything rests on the **master password**: anyone with a copy of the data
can try passwords offline, as fast as their hardware allows.

### Design

#### Master key

- Derived from the master password with **PBKDF2-HMAC-SHA256, 600,000
  iterations** (the OWASP recommendation for PBKDF2) and a random 16 byte
  salt.
- The database stores the record
  `pbkdf2-sha256$<iterations>$<salt>$<verifier>`, where the verifier is a
  SHA-256 hash of the derived key. It is compared in constant time.
- The platform PBKDF2 is used where available, with a pure Java fallback for
  Android 7.0–7.1 and empty passwords.
- Iteration counts read from files are capped at 10,000,000, so a crafted
  file can't make the derivation run (almost) forever.
- **New master passwords need at least 12 characters.** An existing shorter
  password still works, but logging in with it shows a reminder with a
  "Change" button. The setup and change screens show the minimum below the
  field and count the missing characters.
- There is no strength meter for the master password, since the
  generator's estimate assumes random characters and would make a typed
  password like `Password1234!` look strong.

Rough strength: one high-end GPU tries in the order of 10,000–20,000 master
passwords per second against this derivation. 8 random lowercase letters
last months, a common word or a leaked password minutes, and a random 5 word
passphrase is out of reach.

#### Database

- Each field of each entry is encrypted separately with **AES-256-CBC** and a
  random IV, with the default crypto provider. Android has deprecated asking
  for its BouncyCastle provider by name, see [Sources](#sources).
- The fields are encrypted but **not authenticated**, see
  [Decisions](#decisions).
- The ciphertext length shows the rough length of each value to someone with
  a copy of the database.
- The conversions of old entries are chosen by a data version stored in the
  database, never by the app version, so a version number can't make the
  app convert current entries again and destroy them.

#### Exports and imports

- Encrypted exports ("sq2"): **AES-256-GCM** with a random 12 byte nonce, the
  master key salt and iteration count in a header that is authenticated as
  additional data. They are encrypted with the database key, so they need
  the master password that was current at export time, and a modified file
  is not imported.
- Unencrypted exports are possible, with a warning. After importing one, the
  app offers to delete it.
- Exports have a file format version. A newer format is refused, so an older
  app doesn't import a file and leave out what it doesn't know.
- Exports truncate an existing file and leave out characters XML doesn't
  allow, so a backup can always be imported again.
- Imports read at most 10 MB.
- A very deeply nested file can still crash the app. It can only be a file
  the user picks, and nothing is lost.

#### Legacy formats

- The original Passdroid derives the key with a **single HMAC-SHA256 with a
  fixed key**, no salt and no iterations, so a copy of its database or of an
  export can be attacked very fast. A database with such a key is
  re-encrypted with a PBKDF2 key at the first login, in one transaction that
  is only committed if every entry decrypts.
- Its "sqt" exports (AES-CBC, zero IV, no authentication) and the even older
  ECB and zero IV entry formats can still be read, so old data can be
  imported. An "sqt" export is only written for a database that still has a
  legacy key because its upgrade failed; the old entry formats are never
  written.

#### Session and logout

- Default: log out **5 seconds** after the app goes to the background, or
  never.
- **Every logout wipes the master key** and drops the decrypted entries,
  whatever its reason.
- A master password change wipes the old key, which would still decrypt the
  exports made before the change.
- A login clears a logout timer left from before it, so it isn't undone
  right away.

#### Data in memory

- All fields of all entries are decrypted when the entries are loaded, and
  kept until the logout.
- Closed password list screens are released, so their decrypted entries
  don't stay in memory after a logout.
- Limits: the key is in memory while the user is logged in, and decrypted
  Java strings can't be wiped.

#### Release signing

- The release APK is signed with a key that the maintainer creates locally
  and keeps backed up. It is never created in CI or in shared storage. The
  Publish workflow signs with it from GitHub secrets and fails without them,
  since Android can't install an unsigned APK.
- The job that has the signing secrets can only read the repository.
  Tagging and creating the release run in a separate job, the only one with
  write access, on the commit that was built.
- Each release publishes the SHA-256 checksum of the APK. It only shows that
  the download is complete; the signature is what Android checks on updates.
- The actions of the workflows are pinned by commit hash. The release is
  created with the `gh` CLI of the runner instead of a third-party action.

### Decisions

Trade-offs that were made on purpose:

- **Shorter existing master passwords still work**, with a reminder after
  each login, so nobody is locked out. Only new master passwords need 12
  characters.
- **The key stays in the app's memory while the user is logged in**, instead
  of in the Android Keystore: every decryption would go through the
  Keystore, a much larger change.
- **Database fields are not authenticated.** Someone who can write the
  database could swap or alter values, but that needs root-level access to
  the app's storage. Part of the "vault format v2" in
  [Open items](#open-items).
- **PBKDF2 instead of Argon2id.** Argon2id is memory-hard and today's first
  choice, but needs a library and an amount of memory that fits old phones.
  With the 12 character minimum, PBKDF2 is adequate. Also part of the
  "vault format v2".

### Open items

Not done, in rough order of value:

1. **Master password minimum 15** instead of 12, to match NIST SP 800-63B-4
   for a password that is the only factor. A one-line change; existing
   shorter passwords would only get the reminder.
2. **zxcvbn strength meter for the master password**, which knows common
   words, patterns and leaked passwords. Needs a library with word lists of
   about 1–2 MB.
3. **"Vault format v2"**: AES-GCM for the database fields with the entry id
   and field name as associated data, and Argon2id instead of PBKDF2, in one
   migration at login. The riskiest kind of change, since it rewrites the
   whole vault. Only with device testing and a fresh export at hand.

Limitations that are not about security are listed in
[Known limitations](#known-limitations).

### Recommendations

- **Delete old exports and backups of the original Passdroid** wherever they
  are (cloud, email). They use the weak legacy key. If one may have leaked,
  **change the master password**, since cracking an old copy reveals it.
- If your master password is shorter than 12 characters, change it. The app
  reminds you after each login with it.

### Sources

- **PBKDF2 iterations**: OWASP recommends 600,000 iterations for
  PBKDF2-HMAC-SHA256.
  - [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- **Android's BouncyCastle provider**: Android P deprecated asking for it by
  name (`"BC"`), and Android 12 removed many of its algorithms.
  - [Cryptography Changes in Android P](https://android-developers.googleblog.com/2018/03/cryptography-changes-in-android-p.html)
  - [Android 12 behavior changes: BouncyCastle implementation removed](https://developer.android.com/about/versions/12/behavior-changes-all)
- **NIST SP 800-63B-4** (August 2025): at least 15 characters for a password
  that is the only factor, at least 8 with another factor, no composition
  rules, a check against leaked and common passwords, at least 64 characters
  allowed, no forced periodic changes. From secondary sources; the final
  text was not checked directly.
  - [NIST SP 800-63B-4: Deciphering the New Password Security Standard](https://www.sakimura.org/en/2025/10/7710/)
  - [NIST SP 800-63B Rev 4 (Enzoic)](https://www.enzoic.com/blog/nist-sp-800-63b-rev4/)
  - [NIST Recommends Some Common-Sense Password Rules (Schneier)](https://www.schneier.com/blog/archives/2024/09/nist-recommends-some-common-sense-password-rules.html)

## Tests

Local JVM unit tests (`./gradlew testDebugUnitTest`):

| Class | Covers |
|---|---|
| `FileExportImportTest` | Cleartext, `sq2` and `sqt` round trips with awkward values (`]]>`, Unicode); invalid characters; wrong key, modified file, unreasonable iteration count; a wrong key told apart from a file that can't be imported; format versions of older files, files of each older format, newer and invalid versions |
| `MasterKeyTest` | PBKDF2 against the RFC 7914 vectors, empty and long passwords, UTF-8, create/unlock, random salts, legacy records, malformed records, minimum length |
| `PasswordEntryTest` | Reading and writing entries compatible with BouncyCastle, the zero IV and ECB conversions |
| `SessionTest` | Both kinds of logout wipe the key |
| `DbMigrationTest` | Data versions of databases that stored the app version, invalid versions |

BouncyCastle (`bcprov-jdk18on`) is only a test dependency, to check that the
default provider reads and writes what the BouncyCastle provider wrote. There
are no instrumented tests; screens and the clipboard are only tested by
hand, see [Manual tests](#manual-tests).

Lint runs in CI (`lintDebug`) and must have no errors.

### Manual tests

What the unit tests don't cover, worth checking on a device before a
release:

- Export over an existing, longer export file and import it again.
- Open an entry: copy the password. Edit it, view it again, delete it. Copy
  a password with a long press in the list, also while searching.
- Open the search: the field fills the action bar.
- Edit an entry, clear the system name and save: it is refused.
- Set up or change the master password with fewer than 12 characters: the
  note below the field counts the missing characters, and the password is
  refused. Log in with an existing short one: the reminder appears.
- Open the password generator in portrait and landscape and change the
  length and the characters: the bar follows.
- Install the new APK over the previous release: the entries are there
  after the login. Import an export of the original Passdroid and one of
  the previous release.
- The home screen and the About screen show Passdroid++ and the version.

## Making changes

### Adding a field to entries

1. `PasswordEntry`: the `enc`/`dec` fields with getters and setters,
   `decryptAll()`, `encryptAll()`.
2. `PasswordData.onCreate()`, and the column lists in `PasswordModel` and
   `DbMigration.changeKey()`.
3. `Constants.DBVERSION` + 1, and an `ALTER TABLE` step in
   `DbMigration.handleDatabaseUpgrade()`.
4. `DbMigration.isDecrypted()`, so a password change checks the field too.
5. `FileExporter.createXmlTree()` and `FileImporter`'s `parseSystemNode()`.
   Old files must still import without it. Increase
   `FileExporter.FORMAT_VERSION`, so earlier versions refuse the files
   instead of leaving the field out.
6. The add, edit and show layouts and activities, and the intent extras
   between them.
7. Tests in `FileExportImportTest`.

### Adding a setting

Add it to `res/xml/settings.xml`.

### Changing a file format

Keep reading every older format; exports are the users' backups. Write a new
format only with a new signature or a higher `FileExporter.FORMAT_VERSION`,
add it to the table in `FileImporter.getFormatVersion()`, and add a round
trip test plus a test that reads a file in the old format. If entries are
stored differently in the database, increase `DbMigration.DATA_VERSION` and
add the conversion to `postLoginMigration()`.

## Releasing

Releases are made with the "Publish" workflow,
`.github/workflows/publish.yml`, from the Actions tab ("Run workflow"). It
runs the tests and lint, builds and signs the APK, tags the commit
`v<version>`, and creates a GitHub release with the APK and its checksum.
The work is done by `publish.sh`, which runs locally as well, see
`./publish.sh --help`.

### Version and version code

- **Version** (`publish.sh version`): the version numbers continue those of
  Passdroid, so the major version stream (`base_ver`) is 3. The version is
  the latest `v3.x.y` tag, bumped by "Bump Type" (major, minor or patch, by
  default patch). Run from master, it is the next release, e.g. 3.2.1 after
  3.2.0. Run from another branch, or with "Preview", it is the next preview,
  e.g. 3.2.1-preview.1, then 3.2.1-preview.2, published as a pre-release.
  With no tag in the stream it is `<base_ver>.0.0`. "Version" sets the
  version directly. An existing tag is an error.
- **Version code**: the number of the workflow run, or the "Version code"
  input. Keep the file name `publish.yml`: renaming it starts the run numbers
  at 1 again, and Android doesn't install an update with a lower version
  code.

### Steps

1. **Build** (`publish.sh build`): `testDebugUnitTest lintDebug
   assembleRelease` with that version.
2. **Package** (`publish.sh package`): checks with `aapt2` that the unsigned
   APK has that version, signs it with `apksigner` (v2 and v3, no v4 file),
   verifies it, and writes `release/passdroid-<version>.apk` and
   `release/checksums.txt`. Without the signing secrets the run fails:
   Android never installs an unsigned APK.
3. **Release**, in a second job, the only one with write access: tags the
   built commit `v<version>` (`publish.sh release`; the tag may already be
   there when a failed release is run again) and creates the GitHub release
   with `gh release create --generate-notes`, as a pre-release if the
   version has a `-`.

The "Step" switches turn off building, packaging or releasing, e.g. to sign
an APK for testing without a release; releasing needs the other two.
"Source branch" builds another branch with the `publish.sh` of the branch
the workflow runs from. Runs don't overlap (`concurrency`), so two runs
can't pick the same version. The run summary shows the version code, the
commit and the checksum. Both workflows pin their actions by commit hash.

### Signing key

The APK is signed with the key from these repository secrets:

- `RELEASE_KEYSTORE_BASE64`: the keystore file, base64 encoded
- `RELEASE_KEYSTORE_PASSWORD`
- `RELEASE_KEY_ALIAS`: only needed if the keystore holds more than one key
- `RELEASE_KEY_PASSWORD`: only needed if it differs from the keystore
  password

GitHub hides the values of secrets in the logs, so a secret must not be a
word that appears in them, like "passdroid".

Create the keystore yourself, on your own computer, never in CI or in shared
storage, for example with

```
keytool -genkeypair -v -keystore passdroid-release.jks -alias passdroid -keyalg RSA -keysize 4096 -validity 10000
```

Keep it and its password backed up: Android only installs an update that is
signed with the same key. Locally, `publish.sh package` signs with the
keystore in `KEYSTORE_FILE`, and reads the passwords from
`KEYSTORE_PASSWORD` and `KEY_PASSWORD` or asks for them.

## Known limitations

Code that does nothing:

- `IdleLogoutCallback` and the countdown overlay in `PasswordListActivity`
  are never triggered.
- `TimeoutReceiver` is empty and not in the manifest.
  `Crypto.verifyAes256Implementation()`, `Utils.logoutConfirmDialog()` and
  `Utils.unescapeXMLChars()` are never called.
- `Constants.DEBUG` only adds a "Drop DB" menu item, which does nothing.
  `Constants.CLEARDB` is not used. `Utils.debug()` logs whatever
  `Constants.DEBUG` is; nothing secret is logged.

Limitations:

- The four `Timeout*Activity` base classes are copies of each other, and
  each screen that starts an external activity repeats the timeout check in
  `onActivityResult()`.
- Re-encrypting all entries (password change, legacy upgrade) runs on the UI
  thread.
- The pure Java PBKDF2 of Android 7.0–7.1 hashes the HMAC pads again in
  every iteration, which makes it about twice as slow as needed.
- A progress dialog that is open while the screen rotates is lost with its
  result; the user has to repeat the action.
- A very deeply nested import file can crash the app (`StackOverflowError`
  is not caught). Only a file the user picks; nothing is lost.
- A carriage return in a value comes back as a line feed after export and
  import, since XML normalizes line ends. Fixing it needs an escape in the
  export format.
- Import replaces all entries; there is no merge.
- Many strings are hardcoded in English in the code instead of resources.
