# Build signed release on GitHub

## 1. Create repository

```bash
unzip ClashMetaForAndroid-github-signed-build.zip
cd ClashMetaForAndroid-main
git init
git add -A
# Optional: keep keystore out of git and use secrets only:
# git rm --cached release.keystore signing.properties KEYSTORE_CREDENTIALS.txt
git commit -m "CMFA with Airplane Recovery (Shizuku)"
git branch -M main
git remote add origin https://github.com/YOUR_USER/YOUR_REPO.git
git push -u origin main
```

Initialize submodules after clone if needed:

```bash
git submodule update --init --recursive
```

## 2. Configure Actions secrets

Repo → **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|--------|--------|
| `SIGNING_KEYSTORE_BASE64` | output of `base64 -w 0 release.keystore` |
| `SIGNING_STORE_PASSWORD` | see `KEYSTORE_CREDENTIALS.txt` |
| `SIGNING_KEY_ALIAS` | `cmfa-release` |
| `SIGNING_KEY_PASSWORD` | same as store password |

## 3. Run workflow

- **Build Signed APK** (`build-signed-apk.yml`) — manual or on push; uploads APK artifacts.
- **Build Release** (`build-release.yaml`) — tag + GitHub Release (needs `release-tag` input).

## 4. Local build

Requires JDK 21, Android SDK, Go (for core).

```bash
cp signing.properties.example signing.properties   # if needed
# ensure release.keystore + signing.properties exist
./gradlew app:assembleMetaRelease
```

Output (ARM64 only, single APK): `app/build/outputs/apk/meta/release/*.apk`

## 5. Airplane Recovery feature

See `AIRPLANE_RECOVERY_SHIZUKU.md`. Requires Shizuku on device (no root).

## Workflows (push behavior)

| Workflow | Runs on push to main? |
|----------|------------------------|
| **Build Signed APK** | Yes (signed Meta release) |
| Build Debug | No (manual only) |
| Build Pre-Release | No (manual only) |
| Build Release | No (manual + release tag) |
| Update dependencies | No (manual / repository_dispatch) |

