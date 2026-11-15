# Releasing Mumcord Mobile

Releases are built by GitHub Actions (`.github/workflows/release.yml`). Pushing a version tag such as `v0.1.0` builds the Android app, signs it with the release key and creates a **draft** GitHub release with these files:

| File                                    | What it is                                                                                 |
| --------------------------------------- | ------------------------------------------------------------------------------------------ |
| `mumcord-mobile-0.1.0-arm64.apk`        | The app for Android 12 and later (64-bit ARM phones and tablets), signed with the release key |
| `SHA256SUMS`                            | Checksums of all files, so people can check their download                                 |
| `mumcord-mobile-0.1.0-android.cdx.json` | The SBOM: a list of everything the app is built from (CycloneDX format)                   |
| `THIRD_PARTY_NOTICES.txt`               | The licences of all third-party software in the app                                       |

Nothing becomes public until you publish the draft. The iOS release (TestFlight) is not part of this workflow yet.

## Versions

The tag decides the version; nobody changes it in the code by hand.

- The tag is `v` plus three numbers, for example `v0.1.0`, `v0.2.0`, `v1.0.0`. Tags with a suffix (`v0.1.0-rc.1`) are refused. The second and third number stay below 100.
- **Version name** (what people see): the tag without the `v`, for example `0.1.0`.
- **Version code** (what Android compares to decide that an APK is an update): first number × 10000 + second × 100 + third. `0.1.0` is 100, `0.2.3` is 203, `1.0.0` is 10000. Every new release must have a higher version than the last one, otherwise Android refuses to install it over the old one.
- Builds that are not releases (on your computer, in CI, test runs of this workflow) are version `0.1.0` with code 1, or `0.0.0-dryrun+<commit>` with code 1 in test runs.

The release notes name the Mumcord engine the app contains, read from `engine.lock.toml`: a Mumcord release such as `v0.9.0`, or a commit while the engine has no release yet. The workflow shows a warning in the second case. The specification wants a public release to contain a released engine, so pin a Mumcord release tag before the first public release where you can.

## One-time setup: the signing key

Android only installs an update when it is signed with the same key as the installed app. **The key is the app's permanent identity.** If it is lost, nobody can update any more: every user has to uninstall the app (and loses their data on the phone) and install it again. If someone else gets it, they can make an "update" that Android accepts. So:

- the key exists only in two places: the GitHub environment below and your offline backup;
- it never goes into the repository, a chat, an email or a cloud folder.

### 1. Create the key (once, on your computer)

You need `keytool`, which comes with every Java installation (on a developer machine of this project: `~/.local/share/mumcord/jdk-*/bin/keytool`). Run in a terminal, in a folder outside the repository:

```sh
keytool -genkeypair -v -keystore mumcord-mobile-release.p12 -storetype PKCS12 \
  -alias mumcord-mobile -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=Mumcord Mobile, O=Mumcord"
```

It asks for a password twice. Choose a long one (a password manager can make one). The key is valid for about 27 years. Its alias, the name inside the file, is `mumcord-mobile`.

Write down the key's fingerprint; people can compare it with the one of a downloaded APK:

```sh
keytool -list -v -keystore mumcord-mobile-release.p12 | grep SHA256
```

### 2. Make the offline backup

Copy `mumcord-mobile-release.p12` to at least two offline places (for example two USB sticks kept in different places) and store the password in your password manager. Check once that the copy opens: `keytool -list -keystore <copy>` with the password.

### 3. Create the GitHub environment `android-release`

The release key is only given to the signing step of a tagged release. An environment makes GitHub enforce that.

1. Open the repository on GitHub → **Settings** → **Environments** → **New environment**.
2. Name: `android-release` (exactly this). Click **Configure environment**.
3. Under **Deployment branches and tags**, choose **Selected branches and tags** → **Add deployment branch or tag rule** → **Ref type: Tag**, pattern `v*` → **Add rule**. Now no branch and no pull request can use the key, only version tags.
4. Optional: under **Required reviewers** add yourself. Every release then waits until you click **Approve** in the run.

### 4. Add the four secrets to the environment

On the same page, under **Environment secrets**, click **Add environment secret** four times:

| Name                        | Value                                                                    |
| --------------------------- | ------------------------------------------------------------------------ |
| `ANDROID_KEYSTORE_BASE64`   | The key file as text (base64), see below                                 |
| `ANDROID_KEYSTORE_PASSWORD` | The password you chose in step 1                                         |
| `ANDROID_KEY_ALIAS`         | `mumcord-mobile`                                                         |
| `ANDROID_KEY_PASSWORD`      | The same password as `ANDROID_KEYSTORE_PASSWORD` (PKCS12 files have one) |

The key file as text: on Linux `base64 -w0 mumcord-mobile-release.p12 > keystore.txt`, on macOS `base64 -i mumcord-mobile-release.p12 -o keystore.txt`. Open `keystore.txt`, copy everything into the secret, then delete `keystore.txt`. With the GitHub command line tool instead of the web page:

```sh
base64 -w0 mumcord-mobile-release.p12 | gh secret set ANDROID_KEYSTORE_BASE64 --env android-release --repo Mumcord/mumcord-mobile
gh secret set ANDROID_KEYSTORE_PASSWORD --env android-release --repo Mumcord/mumcord-mobile
gh secret set ANDROID_KEY_ALIAS --env android-release --repo Mumcord/mumcord-mobile --body mumcord-mobile
gh secret set ANDROID_KEY_PASSWORD --env android-release --repo Mumcord/mumcord-mobile
```

(`gh secret set` without `--body` asks for the value, so the password does not end up in your shell history.)

Secrets cannot be read back, not even by you; the offline backup is the only copy you can open.

## Each release

### 1. Prepare

- `CHANGELOG.md` on `main` has a section for the version, headed `## [0.1.0]` (a date may follow), or a plain `## [Unreleased]` section. Its text becomes "What's new" in the release notes, so write it for users.
- `engine.lock.toml` and `Cargo.toml` pin the engine you want to ship (ideally a Mumcord release tag).
- Work through the manual device checklist, [docs/device-checklist.md](docs/device-checklist.md).
- CI on `main` is green.

### 2. Tag

```sh
git switch main
git pull
git tag -a v0.1.0 -m "Mumcord Mobile 0.1.0"
git push origin v0.1.0
```

### 3. Wait for the workflow

Open **Actions** → **Release** and follow the run of the tag (about 30 to 60 minutes). Its jobs:

1. **Build the release APK**: works out the version from the tag, builds the release APK (arm64 only, without signing), checks that it is the product (`scripts/check-release-apk.sh`: right app id and version, not debuggable, no debug-only screens or test switches), and writes the SBOM, the third-party notices and the release notes. It fails when a dependency has a licence that is not allowed (`about.toml`).
2. **Sign with the release key**: the only job that gets the secrets of `android-release`. It signs the APK with APK signature schemes v2 and v3 (`scripts/sign-apk.sh`), checks the signature and writes `SHA256SUMS`.
3. **Create the draft release**: attaches the files and writes the notes. Running it again for the same tag replaces the files of the draft; a published release is never changed.

### 4. Check and publish

1. Open **Releases** on GitHub; the new release is marked **Draft**.
2. Check the notes (engine line, download section, "What's new") and the four files.
3. Optionally install the APK on a phone over the previous version: it must install as an update and keep all data.
4. Click **Publish release**.

### 5. After the release

Rename `## [Unreleased]` in `CHANGELOG.md` to `## [0.1.0] - <date>` and start a new empty `## [Unreleased]` section on top, in a normal pull request.

## Test run without the release key

Every pull request that changes the release pipeline (the workflow, its scripts, `about.toml`, the app's build file or the version catalog) runs the same build as a test, and **Actions → Release → Run workflow** starts one by hand. A test run signs with a throwaway key made inside the job, never touches the environment or its secrets, and creates no release. Its files are kept for 14 days as the run artifact `android-dry-run`. Its APK cannot update a real release: its key and version code differ.

## Building and checking a release locally

The same scripts work on a developer machine (toolchain from `scripts/setup-android.sh`, plus `cargo-about` and `cargo-cyclonedx` at the versions in `scripts/license-tools.env`):

```sh
eval "$(scripts/setup-android.sh --env)"
(cd android && ./gradlew assembleRelease :app:cyclonedxDirectBom -Pmumcord.versionName=0.1.0 -Pmumcord.versionCode=100)
scripts/check-release-apk.sh android/app/build/outputs/apk/release/app-release-unsigned.apk 0.1.0 100
scripts/generate-sbom.sh 0.1.0 android/app/build/reports/cyclonedx-direct/bom.json mumcord-mobile-0.1.0-android.cdx.json
scripts/generate-notices.sh 0.1.0 android/app/build/reports/cyclonedx-direct/bom.json THIRD_PARTY_NOTICES.txt
```

`scripts/sign-apk.sh` signs with any key given through its environment variables; never use the release key on a developer machine.
