# Google Play release checklist

This checklist covers the Play-facing work for package `com.jarves.mh`. It does not resolve the separate policy and technical review required for the downloadable local Linux and Claude Code runtime.

## Implemented in the project

- [x] Stable application ID: `com.jarves.mh`
- [x] API 36 Play build switch
- [x] ARM64-only native output for Play builds
- [x] Android 16 KB page-size alignment verified for 64-bit libraries
- [x] Foreground-service types, permissions, and subtype descriptions declared
- [x] Notification permission requested from a user action
- [x] Cleartext network traffic limited to loopback preview hosts
- [x] Android Keystore-backed API-key encryption
- [x] Release builds exclude local test API keys
- [x] Privacy policy included in the repository and linked from Settings
- [x] Phone screenshots and English store descriptions present
- [x] Configurable upload-key signing without committing credentials
- [x] Public source, privacy policy, and issue-based support website
- [x] Keychain-backed upload keystore and repeatable signed-release script
- [x] 512×512 Play icon and 1024×500 feature graphic
- [x] Data safety, app-content, foreground-service, and reviewer worksheets
- [x] Signed API 36 ARM64 AAB generated and certificate verified

## Required before uploading

- [x] Publish `PRIVACY.md` at the HTTPS URL configured by `privacyPolicyUrl`
- [x] Create and protect a Play upload keystore
- [ ] Enrol in Play App Signing
- [ ] Reserve and register `com.jarves.mh` in Play Console
- [ ] Increment `appVersionCode` for every uploaded release
- [x] Create a 512×512 Play icon and 1024×500 feature graphic
- [ ] Review phone screenshots against the final release build
- [ ] Add a public support email (the public repository and Issues page are already available)
- [x] Prepare Data safety answers, including data sent to configured AI providers
- [x] Prepare content rating, target audience, ads, app access, and privacy answers
- [x] Prepare both `specialUse` foreground-service declarations
- [ ] Record a reviewer-accessible video showing how each foreground service is started, displayed, and stopped
- [x] Provide reviewer instructions for setup and provider authentication
- [ ] Run Play pre-launch reports on all supported Android versions
- [ ] Complete Anthropic branding, authentication, licensing, and redistribution review
- [ ] Resolve or obtain approval for the local executable-runtime architecture

Account-deletion and Play Billing requirements apply only if Mobile Harness later introduces first-party accounts or sells digital subscriptions in the app.

## Build configuration

Set upload-signing values outside the repository:

```bash
export MH_UPLOAD_STORE_FILE="/absolute/path/mobile-harness-upload.jks"
export MH_UPLOAD_STORE_PASSWORD="..."
export MH_UPLOAD_KEY_ALIAS="mobile-harness-upload"
export MH_UPLOAD_KEY_PASSWORD="..."
```

Create and validate the Play bundle:

```bash
./gradlew \
  -PplayBuild=true \
  -PappVersionCode=1 \
  -PappVersionName=1.0.0 \
  -PprivacyPolicyUrl=https://github.com/techjarves/Mobile-Harness/blob/main/PRIVACY.md \
  playReadinessCheck bundleRelease
```

For local macOS releases, `scripts/build-play-release.sh` retrieves the upload password from Keychain and performs the checks, tests, lint, APK build, and AAB build without printing the secret.

Never commit the keystore, passwords, API keys, or private service credentials.
