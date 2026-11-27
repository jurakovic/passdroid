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
- [Fingerprint unlock](#fingerprint-unlock)
- [Export and import](#export-and-import)
- [2FA codes](#2fa-codes)
- [Password generator](#password-generator)
- [Settings, theme and fonts](#settings-theme-and-fonts)
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
  AndroidX except CameraX for the QR scanner and the AndroidX libraries it
  needs, of which the app uses androidx.core (`ContextCompat`) directly. No
  dependency injection, no Kotlin in the app code.
- `minSdk` 24 (Android 7.0), `compileSdk` and `targetSdk` 37 (Android 17).
- Dependencies: ZXing core (QR decoding and encoding), CameraX (camera
  preview and frames), androidx.core (receivers that are not exported on
  every Android version). Tests: JUnit 4 and BouncyCastle.
- Java package and namespace `com.kodholken.passdroid`, the package of the
  original Passdroid. The application id is `com.jurakovic.passdroid`: the
  original id is signed by its author, so an app with it can't be installed
  next to the original or published under it. Debug builds have
  `com.jurakovic.passdroid.debug` and the name "Passdroid++ Debug", so they
  install next to the release app, with their own data.
- The only permissions are `CAMERA` and `USE_BIOMETRIC`, and the app's own
  signature permission
  `<application id>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which
  androidx.core declares and uses so that the app's runtime receivers are
  not exported before Android 13, see
  [Android platform details](#android-platform-details). It grants nothing
  outside the app. There is no `INTERNET` permission, and the manifest
  removes `ACCESS_NETWORK_STATE`, which `androidx.media3`, a dependency of
  CameraX, declares. Everything stays on the device unless the user exports
  it.

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
- **Debug builds are signed with the keystore in `-PdebugKeystore=<path>`**
  (or `ORG_GRADLE_PROJECT_debugKeystore`), if given, else with the key the
  Android Gradle plugin generates. A relative path is relative to `app/`.
  See [Debug signing key](#debug-signing-key).
- **Release builds are shrunk with R8** (`minifyEnabled`,
  `shrinkResources`) but not obfuscated (`-dontobfuscate` in
  `proguard-rules.txt`), so stack traces stay readable. Without shrinking,
  CameraX and the Kotlin runtime it brings would make the APK about 4 MB
  larger.
- `unitTests.returnDefaultValues = true`, since `FileImporter` logs through
  `android.util.Log`, which is a stub in local unit tests.

The "Build" workflow, `.github/workflows/build.yml`, runs on every push and
pull request that changes the app, the Gradle build or wrapper,
`publish.sh` or the workflow itself (`paths` in the file), not on changes to
the docs or screenshots alone; "Run workflow" on the Actions tab runs it on
any branch. It installs platform `android-37.0` and build tools 36.0.0,
runs `testDebugUnitTest lintDebug assembleDebug assembleRelease`, and
uploads the debug APK, not zipped, as `Passdroid_v<version>_debug.apk`. The
version is `<next release>-build.<run number>`, e.g. 3.1.0-build.42: the
next release is computed from the tags as in [Releasing](#releasing)
(`publish.sh version -p false`), so the checkout fetches them. The version
code is the run number, so each CI build updates the one before. Keep the
file name `build.yml`: renaming it starts the run numbers at 1 again. The
release build is there to check that R8 keeps what the app needs. The debug
APK is signed with the keystore in the secret `DEBUG_KEYSTORE_BASE64`, so
the debug APKs of different runs install over each other, see
[Debug signing key](#debug-signing-key). Without the secret, e.g. in pull
requests from forks, it is signed with a key generated for the run, and a
notice in the run says so. Releases are made with the "Publish" workflow,
see [Releasing](#releasing).

## Source layout

All code is in `app/src/main/java/com/kodholken/passdroid/`.

| Area | Classes |
|---|---|
| Startup | `PassdroidApplication`, `MainActivity`, `InitializeActivity`, `PasswordLengthHint`, `LoginActivity`, `ShortPasswordReminder` |
| Session | `Session`, the four `Timeout*Activity` base classes, `ScreenOffListener` |
| Crypto | `MasterKey` (key derivation), `Crypto` (legacy key, AES helpers), `PasswordEntry` (field encryption), `Base64` |
| Data | `db/PasswordData`, `db/SystemData`, `db/DbMigration`, `PasswordModel`, `PasswordModelListener` |
| Entries | `PasswordListActivity`, `PasswordAdapter`, `FullWidthLayout`, `ShowActivity`, `AddActivity`, `EditActivity`, `PasswordToggle`, `DateField`, `EntryDraft` |
| Clipboard | `Utils.copyPasswordToClipboard()`, `Utils.copyToClipboard()`, `ClipboardClearReceiver` |
| Fingerprint | `BiometricUnlock`, `SettingsActivity` |
| Export and import | `ImportExportActivity`, `FileExportActivity`, `FileExporter`, `FileImportActivity`, `FileImporter`, `FileImporterException`, `ImportPasswordActivity`, `SettingsBackup` |
| 2FA | `Totp`, `TotpListActivity`, `QrScan`, `ScanActivity`, `QrDecoder`, `QrEncoder` |
| Generator | `PasswordGenerator`, `GeneratePasswordActivity`, `DisplayPasswordActivity`, `PasswordStrength` |
| Settings | `SettingsActivity`, `res/xml/settings.xml`, `ThemeSetting`, `FontSetting`, `ChangePasswordActivity` |
| Misc | `Utils`, `Version`, `Constants`, `AboutActivity`, `OverscrollListView`, `RetainedText` |

Resources worth knowing: `res/values-night/` (dark theme colors),
`res/layout-land/` (landscape layouts of the About screen and the
generator), `res/values/arrays.xml` (setting values, generator character
sets, strength ratings), `assets/fonts/` (Roboto Mono and its license).

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
   least 15 characters), stores the record and logs in. Its two fields have
   eye buttons (`PasswordToggle`), as do the three of
   `ChangePasswordActivity`. It can also restore a backup before there is a
   master password, see [Restoring on the welcome screen](#restoring-on-the-welcome-screen).
4. `LoginActivity` derives the key in a background thread, runs the data
   migrations (`DbMigration.postLoginMigration()`), upgrades a legacy master
   key (see [Master key](#master-key)), and starts `PasswordListActivity`. It
   stays below the list, so a logout returns to it.

Back handling: back on the login screen and on the welcome screen exits the
app. It sets `Session.setExitMain(true)` so that `MainActivity` finishes
itself instead of starting that screen again. Back on the password list
closes the search, or else asks whether to log out. Back on the add and
edit screens asks whether to discard unsaved changes, if there are any. On Android 13 and later
back is handled with an `OnBackInvokedCallback`, since Android 16 doesn't
deliver `onBackPressed()` to apps that target it.

## Session and automatic logout

`Session` is a process-wide singleton with the key, the login state, a
"reload the list" flag and the timeout state. Nothing of it is persisted:
when the process dies, the user is logged out.

### Logging out

`Session.setLoggedIn(false)` is the single place where a logout happens,
whatever the reason. It calls the logout listeners with the key, then zeroes
the key array, drops the reference, and calls `PasswordModel.clear()` to drop
the decrypted entries. A `Session.LogoutListener`
(`addLogoutListener()`, `removeLogoutListener()`) can still use the key in
`beforeLogout()`, e.g. to encrypt something with it. A listener that throws
a `RuntimeException` is logged and the logout goes on. `logout()` (the menu
item and back on the list) also sets `loggedOutByUser`, so the login screen
doesn't open the fingerprint prompt right away.

Activities that show data extend one of four base classes:
`TimeoutActivity`, `TimeoutListActivity`, `TimeoutPreferenceActivity` and
`SherlockTimeoutActivity`. They are copies of the same code on different
platform base classes, and `SherlockTimeoutActivity` is identical to
`TimeoutActivity`. Each:

- sets `FLAG_SECURE` on its window,
- registers a receiver for the timeout broadcast that is not exported on any
  Android version (see [Android platform details](#android-platform-details)),
  which logs out and finishes the activity,
- applies the theme and recreates itself when the theme setting changed,
- keeps its content clear of the system bars (`Utils.applySystemBarInsets()`),
- counts resumes and pauses in the session. When the count drops to 0, the
  app has gone to the background (or to another app's activity) and
  `Session.setTimeoutTimer()` runs. `onResume()` runs
  `Session.clearTimeoutTimer()` and finishes the activity if the session is
  no longer logged in.

`LoginActivity` and `InitializeActivity` don't extend one of them, so they
set `FLAG_SECURE` themselves, first thing in `onCreate()`.

### The timeout

- `setTimeoutTimer()` records the device sleep time and, if "Log out on
  close" is on, sets an inexact `ELAPSED_REALTIME` alarm for the logout
  delay (5 s by default, `logout_delay` setting). The alarm sends the
  timeout broadcast, as an immutable `PendingIntent` limited to the app's
  package.
- `clearTimeoutTimer()` cancels the alarm and logs out **if the deadline has
  passed**. The alarm is inexact, and since Android 14 broadcasts are held
  back while the app is cached, so the check on return is what actually
  enforces the timeout.
- While the system file or image picker, or the camera permission dialog,
  is open for the app, `setExtendedTimeout(true)` makes the delay at least 2
  minutes. The activity that started the picker calls `clearTimeoutTimer()`
  itself in `onActivityResult()`, which runs before `onResume()`, and
  finishes if the session ended.
- A login clears a timer and a pause time left from before it, so the login
  isn't undone right away.

### Screen off

- `ScreenOffListener` receives `SCREEN_OFF` and `SCREEN_ON`. It is
  registered once per process on the application context (a manifest
  receiver can't get these broadcasts). It calls `Session.screenOff()`,
  which logs out and sends the timeout broadcast if "Log out when the screen
  turns off" is on, and `ClipboardClearReceiver.clear()` if a password was
  copied.
- Since Android 14 these broadcasts are also held back for cached apps and
  only the last one is delivered, which is then usually `SCREEN_ON`. That is
  why `SCREEN_ON` is handled the same way.
- As a backup for the held-back broadcasts, `clearTimeoutTimer()` compares
  the device sleep time (`elapsedRealtime() - uptimeMillis()`, the uptime
  clock stops while the device sleeps) with the value recorded when the app
  went to the background. If the device slept for more than a second, the
  screen was off, and the user is logged out.

### Background work

`Utils.runWithProgress()` runs slow work (key derivation, an import with a
password, reading a QR code from an image) in a plain thread behind a
non-cancelable progress dialog, and the completion on the UI thread, unless
the activity has been destroyed or is finishing. Screens guard against
starting the same work twice with a flag.

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
- Iteration counts read from files and from the database's record are
  capped at `MAX_ITERATIONS` (10,000,000); a record with more, or with fewer
  than 1, doesn't unlock.
- `MIN_PASSWORD_LENGTH` (15) is counted in code points. It only applies to
  new master passwords; a login with a shorter one passes
  `EXTRA_SHORT_PASSWORD` to the list, which shows a reminder with Change,
  Later and "Don't remind me", unless that was chosen before. The choice
  is kept by `ShortPasswordReminder` in the `short_password_reminder`
  attribute, for every vault: one byte, on or off, encrypted with the
  vault key in AES-256-GCM with a random nonce and the attribute name as
  associated data, 40 characters in Base64 either way. The list reads it
  and stores it again with a fresh nonce once after every login
  (`renew()`), so a vault has it from its first login on, short master
  password or not; it is on while it is missing or doesn't decrypt.
  `DbMigration.changeKey()` stores it turned on, with the new key. On the
  setup and change screens `PasswordLengthHint` shows the minimum below
  the field and counts the missing characters while typing. There is no
  strength meter: `PasswordStrength` assumes random characters and would
  rate a typed password like `Password1234!` as excellent.

### Legacy format

The original Passdroid derives the key without a salt or iterations:

- The key is `HMAC-SHA256(key = "notverysecretiv", password)`
  (`Crypto.hmacFromPassword()`).
- The record is the base64 of 32 bytes that, XORed with that HMAC, give 28
  random bytes followed by their CRC32 (`Crypto.verifyPassword()`). So a
  guess costs one HMAC and a CRC. A record of another length fails the
  login.
- `MasterKey.isLegacy()` recognizes it by the missing `pbkdf2-sha256$`
  prefix.

At a successful login with a legacy record, `LoginActivity` also creates a
new PBKDF2 key from the password and calls `DbMigration.changeKey()`, which
re-encrypts all entries and stores the new record in one transaction. If
that fails, the legacy key stays in use and the upgrade is tried again at
the next login. Fingerprint unlock can't be turned on before the upgrade.

### Changing the master password

`ChangePasswordActivity` derives the key from the old password (which
verifies it) and a new key from the new password in a background thread,
then calls `DbMigration.changeKey()`. On success the old session key is
zeroed and replaced, and fingerprint unlock is turned off, since its stored
key is the old one.

`DbMigration.changeKey()` decrypts every entry with the old key and only
continues if every field decrypted. It then re-encrypts all entries, updates
the `key` record, deletes the stored fingerprint key and stores the
reminder setting turned on, with the new key (see [Current format](#current-format)),
all in one SQLite transaction. It runs on the UI thread.

## Database

One SQLite database, `password.db` (`Constants.DBNAME`), in the app's private
storage. Schema version 3 (`Constants.DBVERSION`).

```sql
CREATE TABLE data (id INTEGER PRIMARY KEY AUTOINCREMENT,
                   system TEXT NOT NULL, username TEXT, password TEXT,
                   note TEXT, url TEXT, email TEXT, totp TEXT, date TEXT);

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
| `biometric_key` | The master key encrypted for fingerprint unlock, see [Fingerprint unlock](#fingerprint-unlock) |
| `short_password_reminder` | Whether the list reminds of a short master password after a login (`ShortPasswordReminder`): `base64(nonce (12 bytes) \|\| AES-256-GCM(1 byte, 1 on or 0 off) \|\| tag (16 bytes))`, encrypted with the vault key, with the attribute name as associated data. Stored for every vault after each login, see [Master key](#master-key) |
| `entry_draft` | The unsaved changes of the add or edit screen, if any, until the next login (`EntryDraft`): `v1\|<entry id, -1 for a new entry>\|<saved at, ms>\|`, then the eight fields encrypted like an entry's, in the order of `PasswordData.COLUMNS` and separated by `\|`, each empty if it is `null` |

Two `SQLiteOpenHelper` classes, `PasswordData` and `SystemData`, open the same
database. Either can trigger the upgrade, so both `onUpgrade()` methods only
call `DbMigration.handleDatabaseUpgrade()`. Each table is created on demand
by its helper's `verifyTable()`.

There are two kinds of migration:

- **Schema migrations** by database version, in
  `DbMigration.handleDatabaseUpgrade()`:
  - 1 → 2 adds `note` and `url`,
  - 2 → 3 adds `email`, `totp` and `date` (only if the `data` table exists
    yet). Old entries get `NULL` for the new columns.
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

`decrypt()` returns `null` when a value can't be decrypted: with the wrong
key, or when the stored value is damaged, since one changed byte usually
fails the padding check. An entry with such a field is **damaged**.

- `isDecrypted()` tells whether every stored field of a decrypted copy has
  a value. `DbMigration.changeKey()` uses it to refuse re-encrypting a
  database with the wrong key, or with a damaged entry.
- `canDecrypt()` checks every stored field of an entry without creating a
  string of any value: it decrypts into byte arrays and wipes them. The
  entry screen uses it to find a damaged entry, so the password and the
  2FA secret are still only decrypted when they are shown or copied.

## Loading entries

`PasswordModel` is a process-wide singleton over the `data` table.

- `getPasswords()` loads all rows on first use and decrypts **only the list
  fields**: system, username and email (`decryptListFields()`). Every row
  is loaded, also a damaged one, and the entries are sorted with
  `PasswordEntry.LIST_ORDER`: those whose system didn't decrypt first, then
  by system and by username, ignoring case. Case is ignored without a
  locale (`String.CASE_INSENSITIVE_ORDER`), so the order is the same in
  every language, e.g. Turkish, and consistent; the search lower-cases with
  `Locale.ROOT` for the same reason.
- An entry whose system didn't decrypt is shown as "(can't decrypt)"
  (`Utils.systemName()`) in the list and on the 2FA codes screen. On the
  entry screen a damaged entry shows what did decrypt below a notice, and
  has no Edit, since saving would replace the damaged values with empty
  ones; it can be deleted.
- The password, 2FA secret, note, URL and date stay encrypted in the
  `PasswordEntry` objects. Screens decrypt them when needed with
  `decryptPassword()` etc. and don't keep the result, except the screen that
  shows them.
- `getById()` finds an entry by its database id. `getDecryptedPasswords()`
  returns fully decrypted copies for an export, which the caller drops
  afterwards.
- `setPasswords()` replaces **all** rows in one transaction, for an import.
  It encrypts the entries with the session key, and fails if the user has
  been logged out.
- `clear()` drops everything; `Session` calls it at every logout.
- Listeners (`PasswordModelListener`, i.e. the list screen) are notified
  when the entries change. The list removes itself in `onDestroy()`, since
  the model outlives it.

Screens that change entries write to the database directly and, once the
write succeeded, set `Session.setNeedReload(true)`, which makes the list
reload in `onResume()`.

## Screens and data flow

Screens pass **only the entry id** in intents (`ShowActivity.EXTRA_ID`,
`EditActivity.EXTRA_ID`) and look the entry up in `PasswordModel`, so no
decrypted values go through the system in intents. Two intents carry a
secret that isn't stored yet: the generated password to
`DisplayPasswordActivity`, and the text of a scanned QR code from
`ScanActivity`. The password for an encrypted import is handed back to
`FileImportActivity` inside the process instead, like the generated password
to the add and edit screens: `ImportPasswordActivity.takePassword()` returns
it once and forgets it.

- **`PasswordListActivity`**: `PasswordAdapter` filters by a lowercase
  substring of the system, username or email. The search field is an action
  view in the action bar (`collapsable_search.xml`). The action bar measures
  action views as `wrap_content`, so its root, `FullWidthLayout`, takes the
  whole width it is offered. A long press asks whether to copy the password,
  which is decrypted only on "Yes". The entry is taken from the adapter, not
  the model, so the right one is used while the list is filtered.
  After a login it shows, one after the other: the result of a restore on
  the welcome screen (see
  [Restoring on the welcome screen](#restoring-on-the-welcome-screen)); if
  there is a draft of unsaved changes (see below), "You have an unsaved
  entry from <time> (<system>). Continue editing it?"; and the reminder
  about a short master password, unless it was turned off (see
  [Master key](#master-key)). A rotation closes them without dismissing
  them, so the list keeps in its saved state which are still to come or
  unanswered, and shows them again; only the note that a draft could not
  be restored isn't, since that draft is deleted when the note is shown. It
  offers a draft whenever one exists: a list only finds one until the offer
  is answered, since an editor deletes the draft it stored once it is back
  in front. The offer can't be dismissed without an answer. Continue opens the edit screen of the draft's entry with `EXTRA_DRAFT`,
  or the add screen if the draft is of a new entry or its entry is gone;
  Discard deletes it. A draft that doesn't decrypt, e.g. one stored with a
  legacy key that the login has replaced, is deleted, and the list says it
  could not be restored.
- **`ShowActivity`**: decrypts the note, URL and date for display, and the
  2FA secret once into a `Totp` generator for the live code, refreshed every
  second. The password and the 2FA secret are shown with `PasswordToggle` in
  its "source" mode: hidden as `********` (so the length doesn't show), and
  decrypted from the session key only while visible or when copied. Taps on
  the system, email and username copy them as plain text; the note is copied
  like a secret, since notes often hold recovery codes or PINs, but without
  asking; the password and the secret ask first. With `tap_to_copy` off, a long press does all of
  this instead of a tap (`setupClipboardActions()`, read when the entry is
  opened). The 2FA codes screen and the generator copy with a tap either
  way.
- **`EditActivity`**: works on a fully decrypted copy (`decryptedCopy()`),
  encrypts all fields again on save and updates the row. The system name is
  required, as on `AddActivity`.
- **`AddActivity`**: inserts a new row. The date defaults to the current
  time.
- **Unsaved changes** on the add and edit screens: the values that differ
  from those the screen opened with (`initialValues`, kept across a
  rotation; `EntryDraft.differ()`). Back with changes asks "Discard your
  changes?", with Discard and Keep editing. They are kept as a **draft**
  (`EntryDraft`), encrypted with the session key like an entry, in the
  `entry_draft` attribute:
  - at a logout, from a logout listener of the screen, whatever the reason
    for the logout, unless the screen is finishing after a save or Discard.
    The screen then empties its fields, and is finished as any screen after
    a logout;
  - when the screen stops without finishing or rotating, e.g. for the
    generator or another app, since Android may kill the process in the
    background without a logout. `onResume()` deletes it again while the
    user is still logged in, since the screen still has the changes.

  A save and Discard delete it, as does Discard in the list's offer after
  the next login. With `EXTRA_DRAFT`, the screen fills its
  fields from the draft once it is set up, so they count as changes; the
  edit screen only from a draft of its entry.
- A save on the add and edit screens counts only with the session key,
  every field encrypted (`PasswordEntry.isEncrypted()`) and exactly one row
  written; a delete on the entry screen only if exactly one row was
  deleted. Otherwise the add and edit screens stay open with what was typed
  and say why the entry wasn't saved, e.g. "the storage is full"
  (`Utils.describeWriteError()`), and the entry screen says why it wasn't
  deleted. The list reloads only after a success. An import and a master
  password change check `isEncrypted()` too, inside their transaction, so a
  failure rolls back.
- `DateField` stores dates as local time without a time zone,
  `yyyy-MM-ddTHH:mm`, so they show the same time after the time zone changes,
  and formats them in the device's format, or as `yyyy-MM-dd HH:mm` with the
  date format setting (`format()`, which all dates are shown through). A
  date without a time is stored as `yyyy-MM-dd` and shown as the date alone;
  not as 00:00, which is a time that can be picked. Picking a date opens the
  time picker, whose "No time" stores the date alone, and whose Cancel keeps
  the time the date had, or none. An older version shows a date without a
  time as the stored text.

## Clipboard

- **Secrets** (passwords, 2FA codes and secrets, notes) go through
  `Utils.copyPasswordToClipboard()`:
  - the clip gets the label `"password"` (`ClipboardClearReceiver.LABEL`)
    and the `android.content.extra.IS_SENSITIVE` extra, which hides it in
    the clipboard preview of Android 13 and later;
  - `Session.setPasswordInClipboard(true)` is set;
  - `ClipboardClearReceiver.schedule()` sets an inexact 45 second
    `ELAPSED_REALTIME` alarm with an immutable `PendingIntent` to the
    manifest receiver (not exported), so it fires even if the process was
    frozen or killed.
- **Other values** (system, username, email) go through
  `Utils.copyToClipboard()` with the label `"text"`, and cancel a pending
  clear: the user has copied something else.
- `ClipboardClearReceiver.clear()` runs from the alarm and from the screen
  off listener:
  1. cancels the alarm and resets the flag;
  2. stops if "Auto clear clipboard" is off;
  3. reads the clip description. If it can be read and its label isn't
     `"password"`, the clipboard now holds something else (the standard copy
     of other apps has no label), and it is left alone. Since Android 10 the
     description can't be read in the background; it is then `null` and the
     clipboard is cleared anyway;
  4. clears with `clearPrimaryClip()` (Android 9 and later) or an empty
     clip.

## Fingerprint unlock

`BiometricUnlock`, Android 9 and later.

- **Keystore key**: AES-256-GCM, alias `passdroid_biometric_unlock`, in the
  `AndroidKeyStore`, with `setUserAuthenticationRequired(true)` and
  `setInvalidatedByBiometricEnrollment(true)`; on Android 11 and later
  `setUserAuthenticationParameters(0, AUTH_BIOMETRIC_STRONG)`, i.e. every use
  needs a strong biometric.
- **Turning it on** (`SettingsActivity` → `enable()`): a new Keystore key, a
  cipher in encrypt mode, and a `BiometricPrompt` with that cipher as
  `CryptoObject`. After authentication the master key is encrypted and
  stored as the `biometric_key` attribute:
  `base64(GCM IV) + "$" + base64(ciphertext with tag)`. The prompt works on
  a copy of the session key, so a logout while the prompt is open can't
  store a zeroed key. The copy is wiped when the prompt ends.
- **Unlocking** (`LoginActivity` → `unlock()`): the cipher is initialized
  with the stored IV, the prompt authenticates it, and the decrypted key is
  checked against the master key record (`MasterKey.verifyKey()`) before the
  login. A legacy record is never used with fingerprint unlock.
- **Invalidation**: when a fingerprint is added, the Keystore key becomes
  unusable (`KeyPermanentlyInvalidatedException`); a missing key (e.g. the
  data was copied to another device) is treated the same. Fingerprint unlock
  is then turned off and the user is told to log in with the password.
- **Availability**: Android 11 and later check
  `canAuthenticate(BIOMETRIC_STRONG)`, Android 10 `canAuthenticate()`, and
  Android 9 only whether the device has a fingerprint sensor (key generation
  then fails if no fingerprint is enrolled).
- The setting is a `CheckBoxPreference` that isn't persisted: the state is
  whether `biometric_key` exists.

## Export and import

### XML

Both cleartext exports and the content of encrypted exports are this XML,
UTF-8 encoded:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<passdroid version="3" app-version="0.0.0-dev">
  <system name="GitHub">
    <email><![CDATA[alice@example.com]]></email>
    <username><![CDATA[alice]]></username>
    <password><![CDATA[s3cret]]></password>
    <totp><![CDATA[JBSWY3DPEHPK3PXP]]></totp>
    <note><![CDATA[Recovery codes in the safe]]></note>
    <url><![CDATA[https://github.com]]></url>
    <date><![CDATA[2026-10-25T14:30]]></date>
  </system>
  <settings>
    <setting name="clear_clipboard">true</setting>
    <setting name="close_logout">true</setting>
    <setting name="date_format">device</setting>
    <setting name="display_username">true</setting>
    <setting name="font">default</setting>
    <setting name="font_size">normal</setting>
    <setting name="logout_delay">5</setting>
    <setting name="screen_off_logout">true</setting>
    <setting name="tap_to_copy">true</setting>
    <setting name="theme">system</setting>
  </settings>
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
- `<settings>` holds the exported settings, sorted by name, see
  [Settings, theme and fonts](#settings-theme-and-fonts). Exports of the
  original Passdroid have none, and an import of them keeps the current
  settings.
- The `version` attribute is the file format version,
  `FileExporter.FORMAT_VERSION` (3). `app-version` is the version name of
  the app that wrote it, for information only. Older files have an app
  version in `version` instead, which `FileImporter.getFormatVersion()` maps
  to a format:

  | Format | `version` | |
  |---|---|---|
  | 1 | `1.6` and earlier | Unescaped system names |
  | 2 | `1.7` to `2.3` | |
  | 3 | `2.4`, or `3` | CDATA values, `email`, `totp`, `date`, `<settings>` |

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
Storage Access Framework, no storage permission). It builds the whole file
in memory first, the XML of `PasswordModel.getDecryptedPasswords()`,
encrypted or not, and only then opens the file with mode `"wt"`, which
truncates an existing one, and writes it. So an export that fails while
decrypting or encrypting leaves the file, e.g. an earlier backup, as it
was, and says so. The Storage Access Framework can't replace a document in
one step, so a failure while writing can still leave it empty or partial;
that message says to export again. The stream is closed before success is
reported, since some document providers only store the data then.

An unencrypted export first asks for the master password
(`askMasterPassword()`), in a dialog with its own `FLAG_SECURE` and the
field of `password_dialog.xml`. `checkMasterPassword()` checks it with
`MasterKey.unlock()` against the stored record in `Utils.runWithProgress()`,
as the password change checks the old password, and wipes the derived key
at once. The right password goes on with the export, a wrong one shows a
message and asks again. The fingerprint isn't offered. An encrypted export
doesn't ask: it is encrypted with the database key, so reading it needs the
master password anyway.

Before the file picker opens, the screen checks the entries with
`PasswordEntry.canDecrypt()`. If any are damaged (see [Entry
encryption](#entry-encryption)), a dialog names up to five and asks whether
to export anyway. A field that can't be decrypted is `null` in the
decrypted copy and is left out of the file, as a field the entry doesn't
have; an entry whose system can't be decrypted is exported as "(can't
decrypt)", so its other fields come back with an import too. The success
message then counts the entries that were exported incomplete.

### Importing

`FileImportActivity`:

1. picks a file with `ACTION_OPEN_DOCUMENT` and reads it into memory, at
   most 10 MB (`FileImporter.read()`);
2. detects the format by the first bytes (`sq2`, `sqt`, else XML);
3. **encrypted**: tries the current session key first. If the key is
   wrong, `ImportPasswordActivity` asks for the password, which
   `FileImportActivity` takes with `takePassword()` on every result of that
   screen, also when the session has ended meanwhile, and
   `parseEncryptedWithPassword()` derives the key: PBKDF2 with the salt and
   iterations from the header for `sq2`, the legacy HMAC for `sqt`.
   `FileImporterException.isWrongKey()` tells whether another password could
   help. An `sq2` file that decrypts is authenticated, so a later error,
   such as a newer format, is an error of the file and is shown as such.
   `sqt` is not authenticated, so all its errors count as a wrong key;
4. **cleartext**: parsed directly; afterwards the app offers to delete the
   file (`Utils.deleteDocument()`, which calls
   `DocumentsContract.deleteDocument()`);
5. stores the entries with `PasswordModel.setPasswords()`, which **replaces
   all entries**, and applies the settings of the file with
   `SettingsBackup.apply()`. The message lists the settings that changed,
   one line each with the old value ("Log out on close: off (was on)",
   `SettingsBackup.describe()`), or nothing if none did.

`FileImporter.parse()` decodes the bytes as UTF-8 itself (dropping a byte
order mark) and hands the parser a `StringReader`, so the parser can't pick
another encoding. It then rejects a document type declaration before the root
element (`hasDoctype()`): Android's parser doesn't support turning off entity
expansion, and a DTD could define entities that expand to gigabytes. Values
inside the elements may contain the same text.

### Restoring on the welcome screen

`InitializeActivity` restores a backup before any master password exists.
It can't use `FileImportActivity`, which, like `ImportPasswordActivity`,
extends a timeout base class and finishes when nobody is logged in; it
shares the file reading (`FileImporter.read()`) and the parsing.

1. "Restore from a backup" picks a file with `ACTION_OPEN_DOCUMENT`,
   without the extended timeout, since nobody is logged in. A file that
   can't be read or parsed shows the `import_description_failure` text,
   and the screen stays as it was.
2. **Encrypted**: a dialog asks for the password the file was exported
   with, in the field of `password_dialog.xml`, with `FLAG_SECURE` on its
   window. `parseEncryptedWithPassword()` decrypts it in
   `Utils.runWithProgress()`. A wrong password (`isWrongKey()`) asks again,
   another error shows the read error. After a success the screen asks
   whether to keep that password as the master password, and recommends a
   new one if it is shorter than `MasterKey.MIN_PASSWORD_LENGTH`. Keeping
   it creates a fresh key record with `MasterKey.create()`, as a setup
   does: a new salt and the current iteration count, also for a legacy
   `sqt` export, at the cost of a second key derivation.
3. **Cleartext**, or "Choose a new one": the backup is **pending**. The
   notice above the fields names the file and the number of entries, the
   button reads "Cancel restore", and Continue sets up the master password
   from the fields as in a setup, with the same checks.
4. **Finishing**, either way: the `key` record is stored and the user
   logged in as in a setup, then `PasswordModel.setPasswords()` stores the
   entries and `SettingsBackup.apply()` the file's settings, and the list
   opens, with `EXTRA_SHORT_PASSWORD` for a short kept password. The list
   reports the restore, so that its timeouts already apply while the
   message is open: `EXTRA_RESTORED` (the number of entries),
   `EXTRA_RESTORED_SETTINGS` (the settings that changed, as
   `SettingsBackup.describe()` lists them for an import) and, for a file in
   the clear, `EXTRA_RESTORED_FILE`, for which it asks whether to delete the
   file (`Utils.deleteDocument()`). The access the file picker grants ends
   with the activity that picked the file, so the welcome screen keeps it
   for the list with a persistable grant (`Utils.keepDocumentAccess()`),
   which the list gives up once the question is answered
   (`releaseDocumentAccess()`). A question that is never answered, e.g.
   because of a logout, leaves the grant behind; the list gives up every
   such grant when it opens after the next login
   (`releaseDocumentAccessExcept()`). No other code keeps grants. The reminder about a short master
   password follows once that dialog is closed. If the
   entries can't be stored, the `key` attribute is removed again and the
   user logged out, so there is never a vault without them; the screen says
   the backup could not be restored.

The pending entries, the file's settings, its `Uri` and whether it is
cleartext are kept across a rotation with `RetainedText`. In `onStop()`,
unless the configuration changes, they are dropped, the notice is hidden
and the question whether to keep the backup's password is closed. They are
dropped also when a decryption or a key derivation ends after the screen
has left the foreground: nobody can pick up the phone later and finish a
restore with a password of their own.

## 2FA codes

### Secrets

`Totp.parse()` accepts:

- a base32 key as services show it for manual setup. Spaces, dashes, `=`
  padding and case are ignored. SHA-1, 6 digits, 30 seconds;
- an `otpauth://totp/<label>?secret=...` URI with the optional `algorithm`
  (SHA1, SHA256, SHA512), `digits` (6 to 8), `period` and `issuer`
  parameters.

`otpauth://hotp/` (counter-based) URIs are rejected.

`getCode(time)` implements RFC 6238 on top of the RFC 4226 dynamic
truncation, and is tested against the RFC 6238 test vectors.

### Storing a scanned secret

`Totp.getSecretToStore()`: for a URI with the default parameters only the
base32 key is stored, the same text the user would type; otherwise the whole
URI, so the parameters are kept. `QrScan` fills empty system, email (if the
account contains `@`) or username fields from the issuer and account of the
URI. QR codes without a 2FA secret are rejected, with a specific message for
the export codes of authenticator apps (`otpauth-migration://`).

### Scanning

- **Camera** (`ScanActivity`): CameraX preview plus `ImageAnalysis` at about
  1280×720 with `STRATEGY_KEEP_ONLY_LATEST`. The Y (brightness) plane of each
  frame is copied row by row (rows may be padded) and decoded with ZXing. A
  QR code without a 2FA secret only shows a hint, and scanning goes on.
  CameraX needs a `LifecycleOwner`; the activity implements it itself so that
  it keeps the session handling of `TimeoutActivity`. The camera permission
  is requested when the scanner opens.
- **Image** (`QrScan`): the photo picker (`ACTION_PICK_IMAGES`) on Android 13
  and later, otherwise `ACTION_OPEN_DOCUMENT` for images. The image is
  decoded in a background thread, scaled down if it is larger than 2500
  pixels.
- `QrDecoder` uses ZXing's `QRCodeReader` with `TRY_HARDER` and a
  `HybridBinarizer`, and tries the inverted image when nothing is found, for
  light on dark codes.

### Showing a secret as a QR code

`ShowActivity` builds an `otpauth://totp/` URI with `Totp.toUri()` (issuer =
system, account = username or else email; percent encoded, with `%20` for
spaces; a stored URI is used as it is), encodes it with `QrEncoder` (ZXing
`QRCodeWriter`, error correction M, quiet zone of 4 modules, UTF-8 only when
needed) and shows it in a dialog. The dialog sets `FLAG_SECURE` itself,
since a dialog has its own window, and is dismissed in `onPause()`.

### 2FA codes screen

`TotpListActivity` decrypts the secrets of all entries each time it is
shown and keeps only the `Totp` generators, which hold the decoded keys. A
handler runs every 50 ms to move the bar for the 30 second period and
rebinds the list once a second. Entries with another period show their own
seconds left. A tap copies the code, a long press opens the entry. The title
is the system, followed by the username (or the email) in parentheses if
`display_username` is on.

## Password generator

- `PasswordGenerator` picks every character uniformly with `SecureRandom`
  from one of four character sets: letters and digits (62), letters (52),
  digits (10), or letters, digits and `!#@%&/()?+-_:;*=$` (79), into a
  `char[]` that is wiped afterwards. The length is 1 to 99, 17 by default.
  `GeneratePasswordActivity` stores the length and the set in the
  preferences `passwordLength` and `passwordCharset` when it generates a
  password.
- **Quick generation**: "Generate password" on the add and edit screens
  generates with the stored length and set (`generateWithStoredSettings()`)
  without opening the generator, which the button next to it opens. It asks
  before replacing a password it didn't put in itself, such as the stored
  one or a typed one, and shows the field afterwards, as it does for the
  generator's result.
- **Strength**: `PasswordStrength.getBits()` = ⌊length × log₂(set size)⌋,
  rated very weak below 40 bits, weak from 40, fair from 65, good from 75,
  excellent from 100. The bar is full at 100 bits. The formula is only
  right for random passwords, so the meter is only shown in the generator.
- Opened from the add or edit screen, the result is handed back through the
  static `takeGeneratedPassword()`, which returns the password once and
  forgets it. Opened from the list, it is shown on `DisplayPasswordActivity`,
  where a tap asks whether to copy it.

## Settings, theme and fonts

Settings are default `SharedPreferences`, defined in `res/xml/settings.xml`:

| Key | Default | Used by | Exported |
|---|---|---|---|
| `close_logout` | `true` | `Session.setTimeoutTimer()` | yes |
| `logout_delay` | `5` (seconds: 5, 30, 60, 300) | `Session.getTimeout()` | yes |
| `screen_off_logout` | `true` | `Session.screenOff()`, `clearTimeoutTimer()` | yes |
| `display_username` | `true` | the password list and the 2FA codes screen | yes |
| `tap_to_copy` | `true` | `ShowActivity` | yes |
| `clear_clipboard` | `true` | `ClipboardClearReceiver` | yes |
| `theme` | `system` (`light`, `dark`) | `ThemeSetting` | yes |
| `font` | `default` (`monospace`) | `FontSetting` | yes |
| `font_size` | `normal` (`small`, `large`) | `FontSetting` | yes |
| `date_format` | `device` (`iso`) | `DateField.format()` | yes |
| `biometric_unlock` | not persisted | `SettingsActivity` | no |
| `passwordLength`, `passwordCharset` | 17, 0 | `PasswordGenerator`: the generator and the generate button of the add and edit screens | no |

`SettingsBackup.get()` collects the exported settings (after applying the
defaults, so unchanged settings are included too). `SettingsBackup.apply()`
only takes known keys with valid values: `true` or `false` for the check
boxes, a value of the setting's array resource for the lists. Other values
are ignored and keep the current setting. It writes only the values that
differ from the current ones, and returns those changes in the order of the
settings screen (`SettingsBackup.ORDER`), with the old and the new value.
The check boxes and lists of `SettingsActivity` read their values only when
they are created, and it stays below the import screen. So a setting that
changes while it isn't in front marks it as stale, and it recreates itself
when it comes back.

- **Theme**: `ThemeSetting.apply()` runs in `attachBaseContext()` of every
  activity that shows something. For "light" or "dark" it overrides the
  night mode of the activity's configuration with
  `applyOverrideConfiguration()`, so the `values-night` resources are
  selected as with the system dark theme. The override leaves `fontScale`
  undefined (0), because before Android 8 the `Configuration` constructor
  sets it to 1, which would replace the user's font size. Activities
  remember the theme they were created with and recreate themselves when it
  changed. The themes are based on `Theme.Material`, because device makers
  restyle `DeviceDefault`.
- **Fonts**: `FontSetting` applies Roboto Mono (from `assets/fonts`) and a
  scale of 0.85, 1 or 1.2 to the values on the entry screens and in the
  lists. Labels keep their size. Sizes are in `sp`, so the system font size
  applies on top. The master password fields get the font alone
  (`applyToPasswordField()`): on the login, setup, change password and
  import password screens, and in the password dialog of an unencrypted
  export and of a restore. It is set either way, Roboto Mono or the
  default font, since Android gives a `textPassword` field a monospace
  font of its own.

## Android platform details

Behavior that depends on the Android version, and where it is handled:

| Android | What | Where |
|---|---|---|
| 7.0–7.1 | No platform PBKDF2-HMAC-SHA256 | `MasterKey.deriveKey()` fallback |
| 9 | `clearPrimaryClip()` | `ClipboardClearReceiver` |
| 9–10 | Biometric availability checks | `BiometricUnlock.isAvailable()` |
| 10 | No clipboard reads in the background | `ClipboardClearReceiver` |
| 11 | `BIOMETRIC_STRONG`, `setUserAuthenticationParameters()` | `BiometricUnlock` |
| 12 | Immutable `PendingIntent`s, exported launcher activity | `Session`, `ClipboardClearReceiver`, manifest |
| 13 | `RECEIVER_NOT_EXPORTED`, photo picker, sensitive clips, back callbacks | `Utils.registerNotExportedReceiver()`, `QrScan`, `Utils`, activities |
| 14 | Held-back broadcasts for cached apps, `accessibilityDataSensitive` | `Session.clearTimeoutTimer()`, layouts |
| 15 | Edge-to-edge windows | `Utils.applySystemBarInsets()` |
| 16 | No `onBackPressed()` for apps that target it | `OnBackInvokedCallback` |

Other settings:

- `android:allowBackup="false"`: no cloud backup. For apps that target
  Android 12 or later it doesn't stop a device-to-device transfer, so on
  some devices moving to a new phone can still copy the encrypted database.
- The text fields of the add and edit screen (`add.xml`), login, setup,
  password change and import password screens, the master password dialog
  of an export and of a restore (`password_dialog.xml`) and the search
  field have
  `imeOptions="flagNoPersonalizedLearning"`, and these layouts have
  `importantForAutofill="noExcludeDescendants"`.
- The password, 2FA secret, 2FA code and generated password views, and the
  master password fields of the setup and password change screens, have
  `accessibilityDataSensitive="yes"` (ignored before Android 14).
- The fields that hold secrets have `saveEnabled="false"`: the master
  password fields of the login, setup, password change and import password
  screens and of the master password dialog of an export or a restore, and
  the password, 2FA secret and note of the add and edit screen. A field otherwise saves its
  text in the saved instance state of its activity, which Android hands to
  the system process when the activity stops. The screens keep the text
  across a rotation with `RetainedText` in
  `onRetainNonConfigurationInstance()` instead, which stays in the process.
  After the process has been killed, the screens of a session finish
  themselves, so their text would be of no use, and the login and setup
  screens start with empty fields.
- Only `MainActivity` is exported. The timeout and screen off receivers are
  registered at runtime as not exported, with
  `ContextCompat.registerReceiver()` (`Utils.registerNotExportedReceiver()`):
  since Android 13 with `RECEIVER_NOT_EXPORTED`, before with the signature
  permission `<application id>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`,
  which AndroidX declares in the manifest. So only the app and the system
  can send to them, and before Android 13 also apps signed with the same
  key. The clipboard receiver is declared with
  `exported="false"`.

## Security

How Passdroid++ protects its data, the trade-offs that were made on purpose,
and what is still open. The sections above describe how each part works.

### Threat model

Passdroid++ protects passwords and 2FA secrets **at rest**:

- a lost or stolen phone,
- a copy of the app's database,
- a backup or an export file that ends up somewhere else.

It has **no network access** (no `INTERNET` permission), so nothing it holds
can be sent anywhere by the app itself.

Out of scope, as for any app on Android:

- someone who has the phone while Passdroid++ is unlocked,
- malware with root or a kernel exploit, which can read the app's memory or
  record the master password as it is typed.

One action is hardened beyond that all the same, since it takes everything
out at once: an unencrypted export asks for the master password.

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
- Iteration counts read from files and from the key record are capped at
  10,000,000, so a crafted file or a damaged record can't make the
  derivation run (almost) forever.
- **New master passwords need at least 15 characters**, as NIST SP 800-63B-4
  asks for a password that is the only factor (see [Sources](#sources)). An
  existing shorter password still works, but logging in with it shows a
  reminder with a "Change" button, until the user chooses "Don't remind
  me", which a new master password resets. The setup and change screens
  show the minimum below the field and count the missing characters.
- There is no strength meter for the master password, since the
  generator's estimate assumes random characters and would rate a typed
  password like `Password1234!` as excellent.

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
- The setting of the reminder about a short master password is stored for
  every vault, encrypted with the vault key in AES-256-GCM, again with a
  fresh nonce after every login, and of the same length on and off. So a
  copy of the database shows neither whether the master password is short
  nor whether the reminder was turned off. Two copies show whether there
  was a login between them, since every login stores the setting again,
  but not whether it changed.
- The conversions of old entries are chosen by a data version stored in the
  database, never by the app version, so a version number can't make the
  app convert current entries again and destroy them.

#### Exports and imports

- Encrypted exports ("sq2"): **AES-256-GCM** with a random 12 byte nonce, the
  master key salt and iteration count in a header that is authenticated as
  additional data. They are encrypted with the database key, so they need
  the master password that was current at export time, and a modified file
  is not imported.
- Unencrypted exports are possible, with a warning. They ask for the master
  password, so a borrowed unlocked phone can't hand over the whole vault in
  the clear. After importing one, the app offers to delete it.
- A backup can be restored on the welcome screen, before the first login.
  Its decrypted entries wait there only while the screen is in the
  foreground (and across a rotation), in a window with `FLAG_SECURE`, and
  are dropped when it leaves. Keeping the backup's password creates a new
  key record, never the one of the file.
- Exports have a file format version. A newer format is refused, so an older
  app doesn't import a file and leave out what it doesn't know.
- The settings are included in exports, except fingerprint unlock. Imported
  settings are checked against the known settings and their allowed values.
  The import lists every setting it changed, so turning off "Log out on
  close", the screen off logout or the clipboard clearing doesn't go
  unnoticed.
- Exports replace an existing file only once the export is complete, and
  leave out characters XML doesn't allow, so a backup can always be
  imported again. Fields that can't be decrypted are never left out
  without the user being told.
- Imports read at most 10 MB and reject files with a document type
  declaration, which could declare entities that expand to a lot of text.
  Only the part before the root element is checked, so values can contain
  the same text, and the file is always read as UTF-8, so the declaration
  can't be hidden in another encoding.
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

#### Fingerprint unlock

Android 9 and later, off by default.

- The master key is encrypted with an **AES-256-GCM key in the Android
  Keystore** that needs strong biometric authentication for every use
  (`BIOMETRIC_STRONG`, `CryptoObject`) and is invalidated when biometrics are
  added.
- A key unlocked this way is checked against the master key record before it
  is used.
- Changing the master password turns fingerprint unlock off.
- Turning it on keeps its own copy of the key while the prompt is shown, so
  a logout during the prompt can't store a wiped key. The copy is wiped when
  the prompt ends, also when it is cancelled.

#### Session and logout

- Defaults: log out **5 seconds** after the app goes to the background (5,
  30, 60 or 300 s, or never), and **when the screen turns off**.
- Since Android 14 the screen broadcasts are held back while the app is in
  the background, so the app also logs out when it comes back to the
  foreground after the device has slept.
- **Every logout wipes the master key** and drops the decrypted entries,
  whatever its reason.
- A master password change wipes the old key, which would still decrypt the
  exports made before the change.
- A login clears a logout timer and a pause time left from before it, so it
  isn't undone right away.
- The screen off listener is registered whenever the process starts, also
  after Android restored the app.

#### Data in memory

- Only the **system, username and email** are decrypted when the entries are
  loaded, for the list and its search.
- The **password and the 2FA secret are decrypted only when they are shown
  with the eye button or copied**, and dropped again. Hidden values are
  shown as `********`, so their length is not shown either.
- The note, URL and date are decrypted when an entry is opened. For the live
  2FA code only the code generator is kept.
- Screens pass entries by their id, so no decrypted values go through the
  system process in intents.
- Typed secrets aren't in the saved instance state either, which the system
  process keeps for a stopped activity: the fields that hold them don't
  save their text, and a rotation keeps it inside the process (see
  [Android platform details](#android-platform-details)).
- The login screen empties its field as soon as it has read the password,
  and when it is left, so the master password doesn't stay in the field
  below the password list for the whole session. The password for an
  encrypted import is handed over inside the process too.
- The last generated password is forgotten once it has been used.
- Unsaved changes of the add and edit screens are stored at a logout as a
  draft encrypted with the vault key, like an entry, until the next login;
  the screen then empties its fields. Nothing decrypted is stored.
- Closed password list screens are released, so their decrypted list fields
  don't stay in memory after a logout.
- Limits: the key is in memory while the user is logged in, and decrypted
  Java strings can't be wiped. On-demand decryption limits how much is
  decrypted at a time, not what could be decrypted.

#### Clipboard

- Copied passwords, 2FA codes and notes are marked as sensitive, so Android
  13 and later hide them in clipboard previews.
- They are **cleared after 45 seconds** (an inexact alarm, so within about a
  minute) and when the screen turns off, if "Auto clear clipboard" is on,
  which is the default. The alarm goes to a receiver declared in the
  manifest, so it also works while the app is frozen or killed.
- Where the app can read the clipboard (in the foreground, or before Android
  10) it only clears its own copy, recognized by its label, never something
  copied in another app. The standard copy of selected text has no label,
  and is kept as well.

#### Screens and input

- The screens that show secrets, and those where the master password is
  typed (login, setup, password change, import password), set
  `FLAG_SECURE`: no screenshots, screen recordings or casts, and no content
  in the recent apps. A password field shows each typed character for a
  moment, so a recording of the login screen would give the master password
  away, and the eye buttons of the setup and password change screens show
  it whole. The setup screen also holds the decrypted entries of a backup
  being restored. The 2FA QR code dialog and the master password dialogs of
  an unencrypted export and of a restore set it too, since a dialog has its
  own window.
- On the entry, login, setup, password change, import password and search
  screens, and in the master password dialogs of an unencrypted export and
  of a restore, the
  keyboard is asked not to learn from the text fields
  (`flagNoPersonalizedLearning`), and these screens are excluded from
  autofill.
- On Android 14 and later the password, 2FA secret, 2FA code and generated
  password views, and the master password fields of the setup and password
  change screens, are only visible to accessibility services that are
  accessibility tools, such as screen readers. Those fields stay
  `textPassword` while the eye button shows them, so the keyboard still
  treats them as password fields and doesn't suggest or learn from them.
  Accessibility services go by the displayed text, so while a field is
  shown they see the password, as with the other views here.

#### 2FA

- TOTP per RFC 6238 (SHA-1/256/512), secrets as base32 or `otpauth://totp/`
  URIs, stored encrypted like the other fields.
- A secret can be **shown as a QR code** to move it to a separate
  authenticator app.
- 2FA secrets in the same vault as the passwords are a trade-off, see
  [Decisions](#decisions).

#### Password generator

- `SecureRandom`, every character chosen uniformly.
- Default **17 characters** of letters and digits, about 101 bits, rated
  excellent. The length and the characters chosen last are remembered.
- Strength meter: bits = length × log₂(characters), rated very weak below
  40, weak from 40, fair from 65, good from 75, excellent from 100 bits. The
  bar is full at 100. About 80 bits can't be found by trying, even if a
  service stores the password poorly; 128 bits can't be found at all. The
  formula is only right for random passwords, which is why the meter is
  only in the generator.

#### Android integration

- Permissions: `USE_BIOMETRIC` and `CAMERA`, which is asked for when the QR
  scanner is first opened. **No `INTERNET`.** The manifest removes
  `ACCESS_NETWORK_STATE`, which a library declares. androidx.core adds the
  app's own signature permission
  `<application id>.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`, which before
  Android 13 keeps the receivers to the app and apps signed with its key,
  and grants nothing else.
- Only the launcher activity is exported. The receivers are not exported,
  and the timeout alarm is an immutable intent for the app itself.
- **Android backup is off** (`allowBackup="false"`). Encrypted exports are
  the backup. On some devices a direct transfer to a new device can still
  copy the (encrypted) data.

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
  each login, so nobody is locked out. Only new master passwords need 15
  characters.
- **The reminder can be turned off** with "Don't remind me": one that
  comes back after every login gets tapped away unread. The choice is kept
  in the database, encrypted, not as a setting, so that an import or a
  restore of settings can't turn it off for another vault's password, and
  changing the master password turns the reminder on again. It is stored
  for every vault, short password or not, and with a fresh nonce after
  every login: a value only vaults with a short password had, even
  encrypted, would show in a copy of the database which ones do, and one
  that only changed when "Don't remind me" is tapped would show the tap in
  two copies. The cost is that every login writes to the database, so two
  copies show whether there was a login between them.
- **2FA secrets next to the passwords.** They still protect against the most
  common attacks: a service's password database leaking, reused passwords,
  password guessing. They don't protect when the vault itself is opened,
  since then both factors are in it. So the advice is to split by
  importance:
  - Critical accounts get their 2FA in a separate authenticator app with its
    own lock and encrypted backups, or better a passkey or a hardware key:
    the main email (it can reset everything else), the Google or Apple
    account of the phone, the bank, GitHub.
  - Everything else may stay in Passdroid++.
  - After moving a secret, delete it from the Passdroid++ entry.
- **No separate 2FA vault inside Passdroid++.** It would need a second strong
  password, its own key, lock and timeout, and changes to the export format
  and a migration. That is about the protection a separate authenticator app
  gives with no work and no risk to the vault.
- **The key stays in the app's memory while the user is logged in**, instead
  of in the Android Keystore: every decryption would go through the
  Keystore, a much larger change. On-demand decryption limits what is
  decrypted at a time instead.
- **Database fields are not authenticated.** Someone who can write the
  database could swap or alter values, but that needs root-level access to
  the app's storage, and backup is off. Part of the "vault format v2" in
  [Open items](#open-items).
- **PBKDF2 instead of Argon2id.** Argon2id is memory-hard and today's first
  choice, but needs a library and an amount of memory that fits old phones.
  With the 15 character minimum, PBKDF2 is adequate. Also part of the
  "vault format v2".
- **Decrypted values are Java strings**, which can't be wiped from memory.
  That can't be solved on Android; on-demand decryption keeps it to what is
  shown or copied.

### Open items

Not done, in rough order of value:

1. **zxcvbn strength meter for the master password**, which knows common
   words, patterns and leaked passwords. Needs a library with word lists of
   about 1–2 MB.
2. **"Vault format v2"**: AES-GCM for the database fields with the entry id
   and field name as associated data, and Argon2id instead of PBKDF2, in one
   migration at login. The riskiest kind of change, since it rewrites the
   whole vault. Only with device testing and a fresh export at hand.

Limitations that are not about security are listed in
[Known limitations](#known-limitations).

### Recommendations

- **Delete old exports and backups of the original Passdroid** wherever they
  are (cloud, email). They use the weak legacy key. If one may have leaked,
  **change the master password**, since cracking an old copy reveals it.
- If your master password is shorter than 15 characters, change it. The app
  reminds you after each login with it, unless you chose "Don't remind me".
- Move the 2FA secrets of critical accounts to a separate authenticator app
  or replace them with passkeys or hardware keys, then delete them from
  Passdroid++.
- Keep encrypted exports as the backup, since Android backup is off.

### Sources

- **PBKDF2 iterations**: OWASP recommends 600,000 iterations for
  PBKDF2-HMAC-SHA256.
  - [OWASP Password Storage Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html)
- **Android's BouncyCastle provider**: Android P deprecated asking for it by
  name (`"BC"`), and Android 12 removed many of its algorithms.
  - [Cryptography Changes in Android P](https://android-developers.googleblog.com/2018/03/cryptography-changes-in-android-p.html)
  - [Android 12 behavior changes: BouncyCastle implementation removed](https://developer.android.com/about/versions/12/behavior-changes-all)
- **`allowBackup="false"`** turns off cloud backup also for apps targeting
  Android 12 and later; on some devices device-to-device transfer can't be
  turned off.
  - [`<application>` manifest element](https://developer.android.com/guide/topics/manifest/application-element)
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
| `FileExportImportTest` | Cleartext, `sq2` and `sqt` round trips with awkward values (`]]>`, `<!DOCTYPE` in values, Unicode); cleartext in UTF-8; an entry without a system refused, a field that didn't decrypt left out; invalid characters; DTD rejection, also behind a comment and in UTF-16; files without some fields; settings; wrong key, modified file, unreasonable iteration count; a wrong key told apart from a file that can't be imported; format versions of older files, files of each older format, newer and invalid versions |
| `MasterKeyTest` | PBKDF2 against the RFC 7914 vectors, empty and long passwords, UTF-8, create/unlock, random salts, legacy records, damaged legacy records, malformed records, iteration counts out of range, minimum length |
| `Base64Test` | Round trips, empty and truncated input rejected |
| `PasswordEntryTest` | Reading and writing entries compatible with BouncyCastle, the zero IV and ECB conversions, on-demand decryption, decrypted copies, old entries without the newer fields; damaged fields (`canDecrypt()`, `isDecrypted()`), `isEncrypted()` with and without a key, the list order with damaged entries and no username, the same order in a Turkish locale |
| `TotpTest` | RFC 6238 vectors, key formats, URI parameters, invalid secrets, what is stored for a scan, issuer and account, URIs for QR codes |
| `QrDecoderTest` | Decoding images and luminance data, light on dark codes, no code, encoder round trip |
| `PasswordStrengthTest` | Bits and ratings |
| `PasswordGeneratorTest` | Lengths, the characters of each set, an unknown set, two passwords differ |
| `SessionTest` | Both kinds of logout wipe the key; logout listeners get the key before it is wiped, a failing one doesn't stop the logout, and none is called without a key |
| `ShortPasswordReminderTest` | The reminder setting encrypted and decrypted, both values of the same length, a fresh nonce each time; not with another key or without one; modified, truncated and other text, other values and other associated data rejected |
| `EntryDraftTest` | Drafts encoded and decoded with empty fields and both kinds of entry id, other text rejected; encrypted and decrypted, not with another key or without one; editor values compared field by field |
| `DateFieldTest` | Dates with and without a time, invalid values, the year-month-day format |
| `DbMigrationTest` | Data versions of databases that stored the app version, invalid versions |
| `VersionTest` | Equal versions have equal hash codes |
| `SettingsBackupTest` | The settings an import changes: only differing values, in the order of the settings screen, unknown settings ignored; every exported setting in `ORDER`, with a title and, for a list, labels |
| `StringResourcesTest` | No line in `res/values*` ends with a backslash, see [Adding a string](#adding-a-string) |

BouncyCastle (`bcprov-jdk18on`) is only a test dependency, to check that the
default provider reads and writes what the BouncyCastle provider wrote. There
are no instrumented tests; screens, the Keystore, the camera and the
clipboard are only tested by hand, see [Manual tests](#manual-tests).

Lint runs in CI (`lintDebug`) and must have no errors.

### Manual tests

What the unit tests don't cover, worth checking on a device before a
release:

- Log in, turn the screen off and on, log in again: no second logout.
- On a fresh install, Back on the welcome screen leaves the app, and
  opening it again shows the welcome screen. On the login screen Back also
  leaves the app.
- On the login screen, a screenshot is refused or black, and the recent
  apps show a blank preview. Logging in with the password and with the
  fingerprint still works. After a wrong password the field is empty, with
  the cursor in it.
- On a fresh install, or after clearing the app's data, restore from the
  welcome screen:
  - an encrypted export with its password, keeping it: the list says how
    many entries were restored and which settings changed, and logging out
    and in again works with that password. A wrong password asks again,
    Cancel returns to the welcome screen;
  - an encrypted export, choosing a new password: the notice names the file
    and the number of entries; a too short or mismatched password is
    refused as in a setup; afterwards only the new password logs in, and a
    new encrypted export opens with it;
  - an unencrypted export: the list asks whether to delete the file, and
    both answers work;
  - an export of the original Passdroid, with its password and a new
    master password: the entries are there;
  - keeping a backup password shorter than 15 characters: the reminder
    follows the list's message;
  - a pending restore survives a rotation; going home and back drops it,
    with the notice, and the button reads "Restore from a backup" again;
  - a file that isn't an export shows the read error, and the screen still
    works. A setup without a restore works as before.
- Import an `sq2` export made with an earlier master password: the password
  screen asks for it, and the import works. Cancel on that screen returns
  to the import screen.
- Export over an existing, longer export file and import it again.
- Export with "Encrypt the exported file" checked: the file picker opens
  right away. Unchecked: the master password dialog appears; Cancel stops,
  a wrong password shows a message and the dialog again, and the right one
  opens the file picker and the export works. A screenshot of the dialog is
  refused, and the keyboard offers no suggestions. After the first login of
  a legacy database (its key upgraded), the right password is still
  accepted.
- In a test vault with a damaged entry (see below), export over a longer
  existing file: Cancel in the damaged entries dialog leaves the file as it
  was, and Export anyway writes a file that imports with the intact fields.
  With a temporary `throw` in `FileExportActivity.buildExport()` (a local
  build, not committed), an export over an existing file keeps its
  content.
- Export, change "Log out on close", the logout delay, the clipboard
  clearing and the theme, and import the export from Settings: the message
  lists those four with their old values, and back on Settings the check
  boxes, the summaries and the theme are the imported ones. Importing the
  same file again lists no settings, nor does an export of the original
  Passdroid.
- Copy a password, wait about a minute: the clipboard is empty. Copy a
  password, copy text in another app and come back within the minute: the
  text is kept. The same for an entry's note, which on Android 13 and later
  is hidden in the clipboard preview, and before shows "Copied to
  clipboard". A copied username shows in the preview, isn't cleared, and
  cancels the clearing of a password copied before.
- Open an entry: show and copy the password and the 2FA secret. Edit it and
  give it a note of several lines, which shows the same on the entry screen
  and after an export and import; view it again, delete it. Copy a password
  with a long press in the list, also while searching. Turn "Tap to copy"
  off and open an entry: a tap copies nothing, a long press copies, asking
  first for the password and the 2FA secret; the URL still opens with a tap.
- Open the search: the field fills the action bar, without an x. Type: the
  x appears. Tap it: the field is empty and keeps the keyboard, all entries
  show, and the x is gone. Edit or delete an entry found by a search and go
  back: the list is still filtered.
- Open the 2FA codes screen, with "Display username" on and off.
- Scan a setup QR code with the camera and from a screenshot.
- Show a 2FA secret as a QR code and scan it with an authenticator app:
  same codes.
- Edit an entry, clear the system name and save: it is refused.
- On the add and the edit screen, Back without changes closes at once. With
  a change it asks first: Keep editing keeps the change, Discard closes the
  screen. A rotation with changes asks nothing.
- With the default settings (logout after 5 seconds), on the add screen
  type a system and generate a password, then switch to another app for 10
  seconds and come back: the login screen. After the login the list offers
  the unsaved entry by its system and time; Continue shows the values, and
  Save stores them. The same on the edit screen opens that entry with the
  changes, and Discard leaves the entry as it was. Turning the screen off
  with the editor in front, then logging in, offers the draft too. Without
  changes, or after Back and Discard, nothing is offered.
- In a debug build, open an entry's edit screen and change the password,
  then make the database read-only with
  `adb shell run-as com.jurakovic.passdroid.debug chmod 400 databases/password.db`.
  Save: a message says the entry wasn't saved, and the screen keeps the
  change. Delete on the entry screen: its message, and the entry stays. The
  same on the add screen. After `chmod 600` again, Save closes the screen
  and the entry screen shows the change.
- Damage two entries of a test vault in a debug build: in
  `adb shell run-as com.jurakovic.passdroid.debug sqlite3 databases/password.db`,
  replace the fifth character from the end of one entry's `system` and of
  another's `password` with a different one (e.g. `A` with `z`). That
  character is in the last block and fully used, while the low bits of the
  one before a trailing `=` are ignored, so changing that one may change
  nothing. Log in: the list loads with "(can't decrypt)" first. Both
  entries open with the notice and without Edit, show what did decrypt, and
  can be deleted. A search still finds the other entries, also typed with
  "I" or "i". (About once in 256 the damaged value still passes the padding
  check and shows as garbage; then change another character.)
- Pick the date of an entry, then "No time" in the time picker: the editor
  and the entry screen show the date alone. Pick a new date and cancel the
  time picker: the time the date had stays, or no time if it had none.
- Export an entry whose note contains `<!DOCTYPE html>`, then import the
  file again.
- Set up or change the master password with fewer than 15 characters: the
  note below the field counts the missing characters, and the password is
  refused. Log in with an existing short one: the reminder appears, also
  again after a rotation. After Later it comes back at the next login;
  after "Don't remind me" it doesn't, also not after a restart of the app.
  In a debug build, in
  `adb shell run-as com.jurakovic.passdroid.debug sqlite3 databases/password.db`,
  `SELECT value FROM system WHERE attribute = 'short_password_reminder';`
  gives 40 characters after each login, different each time, also for a
  vault with a long master password.
- Set Font to Monospace, then to Default. On the change password screen, a
  password shown with the eye button is in Roboto Mono, then in the default
  font, and so is the hint of the master password dialog before an
  unencrypted export. Font size doesn't change these fields. Change Font in
  the settings and log out: the last character typed in the login field,
  shown for a moment while the system's "Show passwords" setting is on, is
  in the font chosen. The setup screen is only reached without a vault: on
  a fresh install it has the default font; for Roboto Mono, set Font to
  Monospace in a debug build with a test vault, force-stop the app, delete
  the vault with
  `adb shell run-as com.jurakovic.passdroid.debug rm databases/password.db`
  (the settings stay) and open the app again.
- On the setup and the change password screen, each eye button shows and
  hides only its field, the cursor stays where it was, and the keyboard
  offers no suggestions while a field is shown. After a rotation all fields
  are hidden and keep their text. On the setup screen a screenshot is
  refused, and the recent apps show a blank preview.
- Rotate the add and the edit screen twice with text typed into every
  field: every value stays, also the hidden password and 2FA secret. The
  same for the master password fields of the login, setup, change password
  and import password screens.
- With the developer option "Don't keep activities" on, open the generator
  from the add screen with every field filled in and go back: the system,
  username, email, URL and date are back, the password, 2FA secret and note
  are empty, since they aren't in the saved state.
- Turn fingerprint unlock on and off, log in with it.
- Open the password generator in portrait and landscape and change the
  length and the characters: the rating and the bar follow.
- On the add screen, "Generate password" fills in a password of the stored
  length and set without the generator, and again without asking; a typed
  password asks first. On the edit screen the first tap asks, and the new
  password is shown. The button next to it opens the generator, whose
  result is shown too and whose settings the next tap uses.
- The keyboard offers no suggestions learned from notes, and no autofill
  service offers to save the master password.
- Install a CI debug APK next to the release app, then the APK of a later
  run over it: both apps keep their data.
- Install the new APK over the previous release: the entries are there
  after the login. Import an export of the original Passdroid and one of
  the previous release.
- The home screen and the About screen show Passdroid++ and the version.

## Making changes

### Adding a field to entries

1. `PasswordEntry`: the `enc`/`dec` fields with getters and setters,
   `decryptAll()`, `encryptAll()`, `decryptedCopy()`, `decryptListFields()`
   if the list shows or searches it, and a `decryptX()` method if it is
   decrypted on demand.
2. `PasswordData`: `COLUMNS`, `onCreate()`, `readEntry()`, `getValues()`.
3. `Constants.DBVERSION` + 1, and an `ALTER TABLE` step in
   `DbMigration.handleDatabaseUpgrade()`.
4. `PasswordEntry.isDecrypted()`, `canDecrypt()` and `isEncrypted()`, so a
   password change, the entry screen, the export and saves check the field
   too. For a field the list shows, also `listFieldsDecrypted()`, which the
   tests check after `decryptListFields()`.
5. `FileExporter.createXmlTree()` and `FileImporter`'s `parseSystemNode()`.
   Old files must still import without it. Increase
   `FileExporter.FORMAT_VERSION`, so earlier versions refuse the files
   instead of leaving the field out.
6. The add, edit and show layouts and activities, `FontSetting` if it shows
   a value.
7. Tests in `FileExportImportTest` and `PasswordEntryTest`.

### Adding a setting

Add it to `res/xml/settings.xml`. If it should be exported, add it to
`SettingsBackup`: the boolean list, or the list map with the array resource
of its allowed values and the map with its labels; its title; and its place
in `ORDER`, the order of the settings screen, in which an import lists what
it changed. Settings that only make sense on one device, like fingerprint
unlock, are not exported.

### Adding a string

A string in `res/values/strings.xml` may wrap over several lines in the
source: aapt2 turns each line break, with the spaces around it, into one
space. Don't end a line with `\`. aapt2 reads it as an escaped line break
and puts a real one into the text, where the screen would break the line
mid-sentence. `\n` is the only way to break a line, e.g. `\n\n` at the start
of a paragraph's line for an empty line before it, as in `about_text`.
`StringResourcesTest` fails on a line that ends with `\`.

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

- **Version** (`publish.sh version`): the latest tag of the major version
  stream, bumped by "Bump Type" (major, minor or patch, by default minor).
  The stream ("Major version stream", `base_ver`) is by default `auto`: the
  major of the latest `v<major>.x.y` tag, release or preview, but at least
  3, since the version numbers continue those of Passdroid. So a major
  release, or its first preview, moves the stream on by itself; a lower
  number picks an older stream, e.g. for a 3.x fix after 4.0.0. Run from
  master, the version is the next release, e.g. 3.3.0 after 3.2.0. Run from
  another branch, or with "Preview", it is the next preview, e.g.
  3.3.0-preview.1, then 3.3.0-preview.2, published as a pre-release. While
  a preview is open (ahead of the latest release), a bump it covers
  continues it and a bump past it starts a new version from the latest
  release: after 3.0.0 and 3.0.1-preview.2, patch gives 3.0.1 (or
  3.0.1-preview.3 from another branch), minor gives 3.1.0 (or
  3.1.0-preview.1). With no tag in the stream it is `<base_ver>.0.0`.
  "Version" sets the version directly. An existing tag is an error.
- **Version code**: the number of the workflow run, or the "Version code"
  input. Keep the file name `publish.yml`: renaming it starts the run numbers
  at 1 again, and Android doesn't install an update with a lower version
  code.

### Steps

1. **Build** (`publish.sh build`): `testDebugUnitTest lintDebug
   assembleRelease` with that version.
2. **Package** (`publish.sh package`): checks with `aapt2` that the unsigned
   APK has that version, signs it with `apksigner` (v2 and v3, no v4 file),
   verifies it, and writes `release/Passdroid_v<version>.apk` and
   `release/checksums.txt`. Without the signing secrets the run fails:
   Android never installs an unsigned APK.
3. **Release**, in a second job, the only one with write access: tags the
   built commit `v<version>` (`publish.sh release`; the tag may already be
   there when a failed release is run again) and creates the GitHub release
   with `gh release create --generate-notes`, as a pre-release if the
   version has a `-`.

The "Step" switches turn off building, packaging or releasing, e.g. to sign
an APK for testing without a release; packaging needs building, and
releasing needs both.
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

### Debug signing key

The Build workflow signs the debug APK with the keystore in the repository
secret `DEBUG_KEYSTORE_BASE64`, so that a CI build installs over an earlier
one and keeps its data. Without the secret it uses a key generated for the
run. To set it up once:

1. Create a keystore with the standard debug alias and passwords, which
   `app/build.gradle` expects:

   ```
   keytool -genkeypair -v -keystore passdroid-debug.jks -alias androiddebugkey -storepass android -keypass android -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
   ```

2. Store the output of `base64 -w0 passdroid-debug.jks` as the secret
   `DEBUG_KEYSTORE_BASE64`.
3. Locally, `./gradlew assembleDebug -PdebugKeystore=<path to
   passdroid-debug.jks>` signs with the same key, so a CI build installs
   over a local one and keeps its data. A local build has version code 1,
   so to install it over a CI build, give it a higher one with
   `-PversionCode=`, or install it with `adb install -r -d`.

Keep the keystore secret even though its passwords are well known: whoever
has it can build an APK that installs over the debug app and reads its data.
Use the debug app for test vaults only.

## Known limitations

- `Utils.debug()` logs in every build, release builds too; nothing secret is
  logged.
- The four `Timeout*Activity` base classes are copies of each other, and
  each screen that starts an external activity repeats the timeout check in
  `onActivityResult()`.
- Re-encrypting all entries (password change, legacy upgrade) runs on the UI
  thread.
- The pure Java PBKDF2 of Android 7.0–7.1 hashes the HMAC pads again in
  every iteration, which makes it about twice as slow as needed.
- A progress dialog that is open while the screen rotates is lost with its
  result; the user has to repeat the action. The same goes for the date and
  time pickers of `DateField`: a date picked before the screen rotates while
  the time picker is open is lost, since only the time picker stores it.
- The 2FA codes screen redraws the bar 20 times a second and computes all
  codes again every second.
- Two loops read a whole stream, `FileImporter.readAll()` and
  `FileImporter.read()`; only the second has the size limit.
- A very deeply nested import file can crash the app (`StackOverflowError`
  is not caught). Only a file the user picks; nothing is lost.
- A carriage return in a value comes back as a line feed after export and
  import, since XML normalizes line ends. Fixing it needs an escape in the
  export format.
- Import replaces all entries; there is no merge.
- Many strings are hardcoded in English in the code instead of resources.

The open security items, e.g. a strength meter for the master password,
are listed in [Open items](#open-items).
