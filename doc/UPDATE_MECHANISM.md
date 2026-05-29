# UPDATE_MECHANISM.md — Cloud Update System Architecture (deep-dive of DOC_8)

> **This is a deep-dive of [`DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md`](./DOC_8_REALTIME_C2_COMMUNICATION_AND_UPDATES.md).** DOC_8 covers the unified C2 + updates story; this document focuses on the Android-side OTA flow (UpdateChecker, UpdateDownloader, UpdateInstaller). For the server side see `UPDATE_SERVER.md` and `UPDATE_SERVER_ARCHITECTURE_SPEC.md`. Phase 1 ships against the mock server (ADR-0009); Phase 1.5 swaps to the real server.

## Objective
Enable the VyzorixAudioRouter daemon to check for, download, and install APK updates from a remote Render backend server, without requiring user interaction beyond a single install confirmation (mandated by Android 13 security).

---

## System Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                    DEVELOPER WORKSTATION                      │
│  - Builds release APK via ./gradlew assembleRelease          │
│  - Signs APK with release keystore                            │
│  - Pushes to GitHub with semantic version tag (v2.1.0)        │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  GITHUB ACTIONS (CI/CD)                       │
│  Trigger: git tag push (v*)                                  │
│  Workflow: push_update_bin.yml                               │
│  Steps:                                                      │
│    1. Build release APK                                      │
│    2. Compute SHA-256 checksum                               │
│    3. Create version.json metadata                           │
│    4. Push APK to server repo /bin/ folder                   │
│    5. Push version.json to server repo /api/v1/version       │
│    6. Deploy to Render (auto-triggered on repo push)         │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                     RENDER BACKEND SERVER                     │
│  - Static file server (nginx/Express)                        │
│  - Serves:                                                   │
│      GET /api/v1/version    -> version.json                  │
│      GET /api/v1/changelog  -> changelog.json                │
│      GET /bin/audiorouter-v2.1.0.apk -> APK binary           │
│  - CORS configured for app domain                            │
│  - HTTPS enforced (TLS 1.2+)                                 │
└──────────────────────────┬──────────────────────────────────┘
                           │
                           ▼
┌─────────────────────────────────────────────────────────────┐
│                  VYZORIX AUDIO ROUTER (DAEMON)                │
│  - NetworkStateMonitor detects internet connectivity         │
│  - UpdateChecker polls /api/v1/version on schedule           │
│  - Compares remote version vs local BuildConfig.VERSION_CODE  │
│  - If update available:                                      │
│      1. Shows "Update available" notification                │
│      2. User taps notification                               │
│      3. UpdateDownloader starts foreground download service   │
│      4. Downloads APK to cacheDir/updates/                   │
│      5. Verifies SHA-256 checksum                            │
│      6. UpdateInstaller triggers ACTION_INSTALL_PACKAGE       │
│      7. System shows "Install this update?" dialog           │
│      8. User confirms -> APK installed                       │
│      9. Daemon restarts with new version                     │
│     10. BootStateRestorer resumes from last known state      │
└─────────────────────────────────────────────────────────────┘
```

---

## Server API Contract

### 1. Version Check Endpoint

**Request:**
```
GET https://your-render-domain.com/api/v1/version
Headers:
  Accept: application/json
  X-App-Version: 2.0.0
  X-App-Build: 38
  X-Device-Model: Nokia C22
  X-Android-Version: 13
```

**Response (200 OK):**
```json
{
  "version": "2.1.0",
  "versionCode": 42,
  "buildNumber": 42,
  "minSdkVersion": 29,
  "releaseDate": "2024-05-24T10:00:00Z",
  "downloadUrl": "https://your-render-domain.com/bin/audiorouter-v2.1.0.apk",
  "checksumSha256": "a1b2c3d4e5f6789012345678901234567890abcdef1234567890abcdef123456",
  "fileSize": 15728640,
  "releaseNotes": "Fixed speaker route drift on Nokia C22. Improved crash detection. Added overlay shortcut.",
  "forced": false,
  "changelog": [
    "Fixed: SpeakerForceEngine loop timing improved from 1000ms to 500ms",
    "Added: OverlayShortcutController for enable/disable toggle",
    "Added: UpdateChecker for remote version polling",
    "Improved: BootStateRestorer now preserves MediaProjection token across reboots"
  ]
}
```

**Response Fields:**
| Field | Type | Required | Purpose |
|-------|------|----------|---------|
| version | String | YES | Semantic version string (e.g., "2.1.0") |
| versionCode | Int | YES | Android versionCode for comparison |
| buildNumber | Int | YES | Internal build number |
| minSdkVersion | Int | YES | Minimum Android SDK required |
| releaseDate | ISO8601 | YES | When this version was published |
| downloadUrl | String | YES | Direct link to APK binary |
| checksumSha256 | String | YES | SHA-256 hash of APK for verification |
| fileSize | Long | YES | APK file size in bytes |
| releaseNotes | String | NO | User-friendly summary |
| forced | Boolean | NO | If true, update is mandatory (no dismiss) |
| changelog | Array | NO | Detailed list of changes |

### 2. Changelog Endpoint

**Request:**
```
GET https://your-render-domain.com/api/v1/changelog
Headers:
  Accept: application/json
```

**Response (200 OK):**
```json
{
  "versions": [
    {
      "version": "2.1.0",
      "versionCode": 42,
      "releaseDate": "2024-05-24T10:00:00Z",
      "changes": [
        "Fixed: SpeakerForceEngine loop timing",
        "Added: OverlayShortcutController"
      ]
    },
    {
      "version": "2.0.0",
      "versionCode": 38,
      "releaseDate": "2024-05-10T08:00:00Z",
      "changes": [
        "Initial release with speaker routing"
      ]
    }
  ]
}
```

### 3. APK Binary Endpoint

**Request:**
```
GET https://your-render-domain.com/bin/audiorouter-v2.1.0.apk
Headers:
  Accept: application/vnd.android.package-archive
Range: bytes=0- (for resume support)
```

**Response (200 OK or 206 Partial Content):**
```
Content-Type: application/vnd.android.package-archive
Content-Length: 15728640
Content-Disposition: attachment; filename="audiorouter-v2.1.0.apk"
ETag: "a1b2c3d4e5f67890..."
Accept-Ranges: bytes
```

**Response (404 Not Found):**
```json
{ "error": "APK not found", "version": "2.1.0" }
```

---

## GitHub Actions Workflow

### `.github/workflows/push_update_bin.yml`

```yaml
name: Build and Push Update Binary

on:
  push:
    tags:
      - 'v*'

jobs:
  build-and-deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Decode keystore
        run: |
          echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 --decode > release.keystore

      - name: Build release APK
        run: |
          chmod +x gradlew
          ./gradlew assembleRelease \
            -Pandroid.injected.signing.store.file=release.keystore \
            -Pandroid.injected.signing.store.password=${{ secrets.KEYSTORE_PASSWORD }} \
            -Pandroid.injected.signing.key.alias=${{ secrets.KEY_ALIAS }} \
            -Pandroid.injected.signing.key.password=${{ secrets.KEY_PASSWORD }}

      - name: Extract version info
        id: version
        run: |
          VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
          VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts)
          echo "version_name=$VERSION_NAME" >> $GITHUB_OUTPUT
          echo "version_code=$VERSION_CODE" >> $GITHUB_OUTPUT

      - name: Compute SHA-256 checksum
        run: |
          sha256sum app/release/audiorouter-v${{ steps.version.outputs.version_name }}.apk > checksum.txt

      - name: Create version.json
        run: |
          cat > version.json << EOF
          {
            "version": "${{ steps.version.outputs.version_name }}",
            "versionCode": ${{ steps.version.outputs.version_code }},
            "buildNumber": ${{ steps.version.outputs.version_code }},
            "minSdkVersion": 29,
            "releaseDate": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
            "downloadUrl": "https://your-render-domain.com/bin/audiorouter-v${{ steps.version.outputs.version_name }}.apk",
            "checksumSha256": "$(cut -d' ' -f1 checksum.txt)",
            "fileSize": $(stat -c%s app/release/audiorouter-v${{ steps.version.outputs.version_name }}.apk),
            "releaseNotes": "Version ${{ steps.version.outputs.version_name }} release",
            "forced": false,
            "changelog": []
          }
          EOF

      - name: Push to server repository
        uses: cpina/github-action-push-to-another-repository@main
        env:
          API_TOKEN_GITHUB: ${{ secrets.SERVER_REPO_TOKEN }}
        with:
          source-directory: '.'
          destination-github-username: 'your-username'
          destination-repository-name: 'vyzorix-update-server'
          user-email: 'bot@vyzorix.com'
          commit-message: 'Release v${{ steps.version.outputs.version_name }}'
          target-branch: 'main'

      - name: Trigger Render deploy
        run: |
          curl -X POST "https://api.render.com/v1/services/${{ secrets.RENDER_SERVICE_ID }}/deploys" \
            -H "Authorization: Bearer ${{ secrets.RENDER_API_KEY }}" \
            -H "Content-Type: application/json"
```

### Required Secrets

| Secret | Purpose |
|--------|---------|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded release keystore file |
| `KEYSTORE_PASSWORD` | Keystore password |
| `KEY_ALIAS` | Key alias name |
| `KEY_PASSWORD` | Key password |
| `SERVER_REPO_TOKEN` | GitHub token with push access to server repo |
| `RENDER_SERVICE_ID` | Render service ID for deployment trigger |
| `RENDER_API_KEY` | Render API key for deployment trigger |

---

## Render Server Setup

### Project Structure (Server Repository)

```
vyzorix-update-server/
├── bin/
│   ├── audiorouter-v2.0.0.apk
│   ├── audiorouter-v2.1.0.apk
│   └── audiorouter-v2.2.0.apk          # New APKs added by GitHub Actions
├── api/
│   └── v1/
│       ├── version.json                 # Updated by GitHub Actions
│       └── changelog.json               # Updated by GitHub Actions
├── public/
│   └── index.html                       # Simple landing page (optional)
├── package.json                         # Express.js dependencies
├── server.js                            # Express server
├── nginx.conf                           # Nginx configuration
└── Dockerfile                           # Container definition
```

### Express.js Server (server.js)

```javascript
const express = require('express');
const path = require('path');
const cors = require('cors');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors({
  origin: ['android-app://com.vyzorix.audiorouter'],
  methods: ['GET', 'HEAD'],
  allowedHeaders: ['Accept', 'X-App-Version', 'X-App-Build', 'Range']
}));

// Serve static files (APK binaries)
app.use('/bin', express.static(path.join(__dirname, 'bin'), {
  setHeaders: (res, filePath) => {
    if (filePath.endsWith('.apk')) {
      res.setHeader('Content-Type', 'application/vnd.android.package-archive');
      res.setHeader('Accept-Ranges', 'bytes');
      res.setHeader('Cache-Control', 'public, max-age=3600');
    }
  }
}));

// Version check endpoint
app.get('/api/v1/version', (req, res) => {
  const versionPath = path.join(__dirname, 'api', 'v1', 'version.json');
  if (fs.existsSync(versionPath)) {
    const version = JSON.parse(fs.readFileSync(versionPath, 'utf8'));
    res.json(version);
  } else {
    res.status(404).json({ error: 'Version info not found' });
  }
});

// Changelog endpoint
app.get('/api/v1/changelog', (req, res) => {
  const changelogPath = path.join(__dirname, 'api', 'v1', 'changelog.json');
  if (fs.existsSync(changelogPath)) {
    const changelog = JSON.parse(fs.readFileSync(changelogPath, 'utf8'));
    res.json(changelog);
  } else {
    res.status(404).json({ error: 'Changelog not found' });
  }
});

// APK download endpoint with range support (resume)
app.get('/bin/:filename', (req, res) => {
  const filename = req.params.filename;
  const filePath = path.join(__dirname, 'bin', filename);

  if (!fs.existsSync(filePath)) {
    return res.status(404).json({ error: 'APK not found' });
  }

  const stat = fs.statSync(filePath);
  const fileSize = stat.size;
  const range = req.headers.range;

  if (range) {
    // Range request (resume support)
    const parts = range.replace(/bytes=/, '').split('-');
    const start = parseInt(parts[0], 10);
    const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
    const chunksize = (end - start) + 1;
    const file = fs.createReadStream(filePath, { start, end });

    res.writeHead(206, {
      'Content-Range': `bytes ${start}-${end}/${fileSize}`,
      'Accept-Ranges': 'bytes',
      'Content-Length': chunksize,
      'Content-Type': 'application/vnd.android.package-archive'
    });
    file.pipe(res);
  } else {
    // Full download
    res.writeHead(200, {
      'Content-Length': fileSize,
      'Content-Type': 'application/vnd.android.package-archive',
      'Accept-Ranges': 'bytes'
    });
    fs.createReadStream(filePath).pipe(res);
  }
});

// Health check
app.get('/health', (req, res) => {
  res.json({ status: 'ok', timestamp: new Date().toISOString() });
});

app.listen(PORT, () => {
  console.log(`Update server running on port ${PORT}`);
});
```

### Render Deployment Configuration

**render.yaml:**
```yaml
services:
  - type: web
    name: vyxorix-update-server
    env: node
    buildCommand: npm install
    startCommand: node server.js
    envVars:
      - key: NODE_ENV
        value: production
      - key: PORT
        value: 3000
    healthCheckPath: /health
    autoDeploy: true
```

**Dockerfile:**
```dockerfile
FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY . .
EXPOSE 3000
CMD ["node", "server.js"]
```

---

## Update Flow (Detailed Daemon Side)

### Phase 1: Network Detection
1. `NetworkStateMonitor` registers `ConnectivityManager.NetworkCallback`
2. On network available: Pings DNS (8.8.8.8:53) to verify internet reachability
3. Checks if connection is WiFi or Cellular (affects download policy)
4. If WiFi or cellular allowed: Proceeds to Phase 2
5. If cellular blocked: Waits for WiFi connection

### Phase 2: Version Check
1. `UpdateChecker` fires on schedule (default: every 6 hours, configurable in `AppConfig`)
2. Makes GET request to `/api/v1/version` with headers:
   - `X-App-Version: BuildConfig.VERSION_NAME`
   - `X-App-Build: BuildConfig.VERSION_CODE`
   - `X-Device-Model: Build.MODEL`
   - `X-Android-Version: Build.VERSION.RELEASE`
3. Parses JSON response
4. Compares `response.versionCode` > `BuildConfig.VERSION_CODE`
5. If update available: Proceeds to Phase 3
6. If no update: Logs "Up to date", schedules next check

### Phase 3: User Notification
1. `UpdateNotificationHandler` posts notification:
   - Title: "Update available: v{remoteVersion}"
   - Body: "{releaseNotes}"
   - Actions: [Download] [Dismiss]
   - Tapping notification triggers `UpdateDownloader.startForeground()`
2. If `forced == true`: No dismiss button, notification persistent

### Phase 4: APK Download
1. `UpdateDownloader` starts `UpdateDownloadService` (foreground, type=dataSync)
2. Downloads APK to `context.cacheDir/updates/audiorouter-v{version}.apk`
3. Shows progress notification (0% -> 100%)
4. Supports resume on network interruption (Range header)
5. On completion: Verifies SHA-256 checksum matches server response
6. If checksum mismatch: Deletes file, logs error, retries up to 3 times
7. If checksum match: Marks state as DOWNLOADED

### Phase 5: Installation
1. `UpdateInstaller` creates `Intent.ACTION_INSTALL_PACKAGE`
2. Uses `FileProvider` to generate `content://` URI for APK
3. Adds flags: `FLAG_GRANT_READ_URI_PERMISSION`, `FLAG_ACTIVITY_NEW_TASK`
4. Starts intent -> System shows "Install this update?" dialog
5. User taps "Install"
6. System verifies APK signature (must match existing app signature)
7. System installs APK, preserving app data
8. System restarts app process
9. `BootStateRestorer` detects restart, resumes from `LastKnownStateDumper` snapshot
10. Daemon returns to RUNNING state

### Phase 6: Post-Install Verification
1. `UpdateStateStore` marks install as SUCCESS
2. `UpdateChecker` polls server again to confirm no newer version
3. Dashboard notification shows: "Updated to v{newVersion}"
4. Old APK files in cacheDir deleted

---

## Error Handling

| Error | Detection | Recovery |
|-------|-----------|----------|
| No internet | NetworkStateMonitor ping fails | Schedule retry in 30 minutes |
| Server unreachable | HTTP timeout after 15 seconds | Schedule retry in 1 hour, exponential backoff |
| Version check fails | Non-200 response | Log error, schedule retry in 6 hours |
| Download interrupted | IOException during stream | Resume via Range header, retry up to 3 times |
| Checksum mismatch | SHA-256 doesn't match server value | Delete file, log warning, retry download |
| Install rejected | PackageManager.INSTALL_FAILED_* | Log error code, notify user, do not retry |
| Signature mismatch | INSTALL_FAILED_UPDATE_INCOMPATIBLE | Uninstall old version, clean install |
| Storage full | IOException: ENOSPC | Delete old cache files, notify user |
| Permission denied | SecurityException on install | Prompt user to enable "Install unknown apps" |

---

## Security Considerations

### 1. APK Signature Verification
- Android enforces that update APK must be signed with the **same certificate** as the installed app
- If signatures don't match: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`
- No workaround possible on stock Android (security feature)

### 2. HTTPS Enforcement
- `network_security_config.xml` blocks all cleartext (HTTP) traffic
- Only HTTPS connections to Render backend allowed
- Certificate pinning optional (recommended for production)

### 3. SHA-256 Checksum Verification
- Server provides checksum in `version.json`
- App computes checksum of downloaded APK
- Mismatch = corrupted or tampered download = delete and retry
- Prevents man-in-the-middle APK substitution attacks

### 4. FileProvider Security
- APK stored in `cacheDir/updates/` (private to app)
- FileProvider grants temporary read-only URI permission to PackageInstaller
- URI expires after install completes
- APK deleted after successful install

### 5. REQUEST_INSTALL_PACKAGES Permission
- User must manually grant "Install unknown apps" permission in Settings
- Cannot be requested programmatically (must open system settings)
- Once granted, persists across reboots
- App should check this permission before attempting install

---

## Configuration Options

### `UpdateConfig.kt` Defaults

| Setting | Default | Description |
|---------|---------|-------------|
| `CHECK_INTERVAL_HOURS` | 6 | How often to poll server for updates |
| `DOWNLOAD_TIMEOUT_SECONDS` | 300 | Max time for APK download |
| `MAX_RETRY_ATTEMPTS` | 3 | Max retries for failed downloads |
| `ALLOW_CELLULAR_DOWNLOAD` | false | Only download on WiFi by default |
| `AUTO_INSTALL` | false | Always requires user confirmation |
| `FORCED_UPDATE_TIMEOUT_HOURS` | 24 | Time before forced update becomes mandatory |

### Override via `AppConfig.kt`

```kotlin
data class UpdateConfig(
    val checkIntervalHours: Long = 6,
    val downloadTimeoutSeconds: Long = 300,
    val maxRetryAttempts: Int = 3,
    val allowCellularDownload: Boolean = false,
    val autoInstall: Boolean = false,
    val forcedUpdateTimeoutHours: Long = 24,
    val serverBaseUrl: String = "https://your-render-domain.com",
    val versionEndpoint: String = "/api/v1/version",
    val changelogEndpoint: String = "/api/v1/changelog",
    val binPath: String = "/bin/"
)
```

---

## Testing the Update Flow

### Method 1: Local Server Testing
1. Run Express server locally: `node server.js`
2. Update `AppConfig.serverBaseUrl` to `http://10.0.2.2:3000` (emulator) or `http://<your-lan-ip>:3000` (device)
3. Place test APK in `bin/` folder
4. Update `version.json` with higher versionCode
5. Trigger update check from dashboard

### Method 2: Mock Server Testing
1. Use `MockWebServer` (OkHttp testing library)
2. Mock version response, changelog response, APK download
3. Test all error conditions (timeout, checksum mismatch, etc.)
4. Run via `DiagnosticTestRunner` on device

### Method 3: Production Testing
1. Deploy to Render
2. Push test APK to `bin/` folder
3. Update `version.json` with higher versionCode
4. Verify update notification appears on device
5. Test download, checksum verification, and install flow

---

## Summary

The update system is designed to be:
- **Safe:** SHA-256 verification, signature enforcement, HTTPS only
- **User-controlled:** No silent installs, explicit confirmation required
- **Resilient:** Resume support, retry logic, error recovery
- **Transparent:** Status visible in notification dashboard at all times
- **Compatible:** Works on stock Android 13 without root or special privileges
- **Automated:** GitHub Actions builds and deploys, Render serves, daemon checks and downloads

The only user interaction required is a single tap on the "Install" button in the system dialog. Everything else happens silently in the background.