# Fix: ./gradlew Permission denied (exit 126)

## Cause
The Gradle wrapper (`gradlew`) lost the Unix executable bit after zip/upload to GitHub.

## Fix applied
Workflows now run `chmod +x gradlew` before building.

## If you only update the repo (without re-uploading full zip)

1. Commit this updated workflow file:
   `.github/workflows/build-signed-apk.yml`

2. Or run once in the repo:
   ```bash
   git update-index --chmod=+x gradlew
   git commit -m "Make gradlew executable"
   git push
   ```

3. Re-run **Build Signed APK**.
