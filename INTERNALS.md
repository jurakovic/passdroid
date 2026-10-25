# Passdroid++ internals

How Passdroid++ is built and how it works: the structure of the code, the
session and logout handling, the key derivation and encryption, the database
and file formats, testing, making changes and releasing.

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
| Startup | `PassdroidApplication`, `MainActivity`, `InitializeActivity`, `LoginActivity` |
| Session | `Session`, the four `Timeout*Activity` base classes, `ScreenOffListener` |
| Crypto | `Crypto` (key derivation, AES helpers), `PasswordEntry` (field encryption), `Base64` |
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
3. `InitializeActivity` checks that the new password was entered the same
   twice, stores the master key record and logs in.
4. `LoginActivity` checks the password against the record, derives the key
   (see [Master key](#master-key)), runs the data migrations
   (`DbMigration.postLoginMigration()`), and starts `PasswordListActivity`.
   It stays below the list, so a logout returns to it.

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

`Session.logout()`, the menu item and back on the list, zeroes the key array
and drops the reference. The other logouts, by the timeout or found when an
activity resumes, only call `Session.setLoggedIn(false)`, which marks the
user as logged out: the key stays in memory until the next login. Neither
drops the decrypted entries of `PasswordModel`.

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

## Master key

The key is derived from the master password without a salt or iterations:

- The key is `HMAC-SHA256(key = "notverysecretiv", password)`
  (`Crypto.hmacFromPassword()`).
- The `system` table stores a record: the base64 of 32 bytes that, XORed
  with that HMAC, give 28 random bytes followed by their CRC32
  (`Utils.generateKey()`, checked by `Crypto.verifyPassword()`). So a guess
  costs one HMAC and a CRC, and someone with a copy of the database can try
  passwords very fast.
- The random bytes come from `java.util.Random`.

### Changing the master password

`ChangePasswordActivity` checks the old password and calls
`DbMigration.changePassword()`, which decrypts every entry with the old key,
encrypts it with the new one and stores the new record, all in one SQLite
transaction. It runs on the UI thread. The session key is then replaced.

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

## Entry encryption

Each field is encrypted separately (`PasswordEntry.encrypt()`):

```
base64( IV (16 random bytes) || AES-256-CBC-PKCS7(key, IV, UTF-8 value) )
```

- `"AES/CBC/PKCS7Padding"` from Android's built-in BouncyCastle provider,
  asked for by name (`"BC"`). The IV comes from `java.util.Random`.
- `NULL` (a field the entry never had) and an empty string are different:
  `null` stays `null`, `""` is encrypted.
- Not authenticated: someone who can write the database could swap or
  corrupt values.
- Legacy formats, only read for the migrations above: AES-ECB, and CBC with
  a zero IV; both with 2 random bytes in front of the value.

`decrypt()` returns `null` when a value can't be decrypted.

## Loading entries

`PasswordModel` is a process-wide singleton over the `data` table.

- `getPasswords()` loads all rows on first use and decrypts **all fields**.
  The entries are sorted by system (case-insensitive), then username.
- `setPasswords()` replaces **all** rows in one transaction, for an import.
  It encrypts the entries with the session key.
- Listeners (`PasswordModelListener`, i.e. the list screen) are notified
  when the entries change.

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

### Encrypted export ("sqt")

`"sqt"` followed by AES-256-CBC with a zero IV over 2 random bytes and the
XML, with the key of the master password, see [Master key](#master-key). It
is not authenticated. This is also the format of the original Passdroid.

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
2. detects the format by the first bytes (`sqt`, else XML);
3. **encrypted**: tries the current session key first. If that fails, for
   whatever reason, `ImportPasswordActivity` asks for the password the file
   was made with, and the key is derived from it;
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
| 9 | `clearPrimaryClip()` | `ScreenOffListener` |
| 10 | No clipboard reads in the background | `ScreenOffListener` |
| 12 | Immutable `PendingIntent`s, exported launcher activity | `Session`, manifest |
| 13 | `RECEIVER_NOT_EXPORTED`, sensitive clips, back callbacks | `Utils.registerNotExportedReceiver()`, `Utils`, activities |
| 14 | Held-back broadcasts for cached apps | `Session.clearTimeoutTimer()` |
| 15 | Edge-to-edge windows | `Utils.applySystemBarInsets()` |
| 16 | No `onBackPressed()` for apps that target it | `OnBackInvokedCallback` |

Only `MainActivity` is exported. The timeout receivers are registered at
runtime as not exported.

## Tests

Local JVM unit tests (`./gradlew testDebugUnitTest`):

| Class | Covers |
|---|---|
| `FileExportImportTest` | Cleartext and `sqt` round trips with awkward values (`]]>`, Unicode); invalid characters; wrong key; format versions of older files, files of each older format, newer and invalid versions |
| `DbMigrationTest` | Data versions of databases that stored the app version, invalid versions |

BouncyCastle (`bcprov-jdk18on`) is only a test dependency: the app asks for
Android's built-in BouncyCastle provider (`"BC"`), which the JVM of the unit
tests doesn't have. There are no instrumented tests; screens and the
clipboard are only tested by hand, see [Manual tests](#manual-tests).

Lint runs in CI (`lintDebug`) and must have no errors.

### Manual tests

What the unit tests don't cover, worth checking on a device before a
release:

- Export over an existing, longer export file and import it again.
- Open an entry: copy the password. Edit it, view it again, delete it. Copy
  a password with a long press in the list, also while searching.
- Open the search: the field fills the action bar.
- Edit an entry, clear the system name and save: it is refused.
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
   `DbMigration.changePassword()`.
3. `Constants.DBVERSION` + 1, and an `ALTER TABLE` step in
   `DbMigration.handleDatabaseUpgrade()`.
4. `FileExporter.createXmlTree()` and `FileImporter`'s `parseSystemNode()`.
   Old files must still import without it. Increase
   `FileExporter.FORMAT_VERSION`, so earlier versions refuse the files
   instead of leaving the field out.
5. The add, edit and show layouts and activities, and the intent extras
   between them.
6. Tests in `FileExportImportTest`.

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
- Re-encrypting all entries for a password change runs on the UI thread.
- A very deeply nested import file can crash the app (`StackOverflowError`
  is not caught). Only a file the user picks; nothing is lost.
- A carriage return in a value comes back as a line feed after export and
  import, since XML normalizes line ends. Fixing it needs an escape in the
  export format.
- Import replaces all entries; there is no merge.
- Many strings are hardcoded in English in the code instead of resources.
