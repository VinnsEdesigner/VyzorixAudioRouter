# VyzorixAudioRouter — Main Codebase Git Workflows

```
.github/
├── workflows/
│   ├── android_build.yml              # Standard CI: Build and lint on PR
│   ├── release.yml                    # Release: Build signed APK, create tag
│   ├── push_update_bin.yml            # Push APK binary + version.json to server repo
│   └── lint.yml                       # Static analysis on every push
```

## `android_build.yml` (Standard CI)

```yaml
name: Android Build CI

on:
  pull_request:
    branches: [main, develop]
  push:
    branches: [develop]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Cache Gradle
        uses: actions/cache@v3
        with:
          path: |
            ~/.gradle/caches
            ~/.gradle/wrapper
          key: ${{ runner.os }}-gradle-${{ hashFiles('**/*.gradle*', '**/gradle-wrapper.properties') }}
          restore-keys: ${{ runner.os }}-gradle-

      - name: Grant execute permission
        run: chmod +x gradlew

      - name: Build debug APK
        run: ./gradlew assembleDebug --no-daemon

      - name: Run lint
        run: ./gradlew lintDebug --no-daemon

      - name: Run detekt
        run: ./gradlew detekt --no-daemon

      - name: Upload debug APK
        uses: actions/upload-artifact@v4
        with:
          name: debug-apk
          path: app/build/outputs/apk/debug/*.apk
```

## `lint.yml` (Static Analysis)

```yaml
name: Lint & Static Analysis

on:
  push:
    branches: [main, develop]
  pull_request:
    branches: [main]

jobs:
  lint:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Run Android Lint
        run: ./gradlew lint --no-daemon

      - name: Run Detekt
        run: ./gradlew detekt --no-daemon

      - name: Upload lint reports
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: lint-reports
          path: |
            app/build/reports/lint-results-debug.html
            build/reports/detekt/detekt.html

      - name: Fail on lint errors
        run: |
          if grep -q "error" app/build/reports/lint-results-debug.html 2>/dev/null; then
            echo "Lint errors found. Please fix them before merging."
            exit 1
          fi
```

## `release.yml` (Release Build)

```yaml
name: Release Build

on:
  push:
    tags:
      - 'v*'

jobs:
  release:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
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
            -Pandroid.injected.signing.key.password=${{ secrets.KEY_PASSWORD }} \
            --no-daemon

      - name: Extract version info
        id: version
        run: |
          VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
          VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts)
          echo "version_name=$VERSION_NAME" >> $GITHUB_OUTPUT
          echo "version_code=$VERSION_CODE" >> $GITHUB_OUTPUT

      - name: Rename APK
        run: |
          mkdir -p release/
          cp app/build/outputs/apk/release/*.apk release/audiorouter-v${{ steps.version.outputs.version_name }}.apk

      - name: Compute SHA-256
        run: |
          cd release/
          sha256sum audiorouter-v${{ steps.version.outputs.version_name }}.apk > checksum.txt

      - name: Create GitHub Release
        uses: softprops/action-gh-release@v1
        with:
          files: |
            release/audiorouter-v${{ steps.version.outputs.version_name }}.apk
            release/checksum.txt
          body: |
            ## VyzorixAudioRouter v${{ steps.version.outputs.version_name }}

            **Version Code:** ${{ steps.version.outputs.version_code }}
            **Build Date:** ${{ github.event.head_commit.timestamp }}
            **SHA-256:** $(cat release/checksum.txt | cut -d' ' -f1)

            ### Changes
            - See commit history for details

      - name: Upload release APK
        uses: actions/upload-artifact@v4
        with:
          name: release-apk
          path: release/audiorouter-v${{ steps.version.outputs.version_name }}.apk
```

## `push_update_bin.yml` (Push to Server Repo)

This is the critical workflow that connects the main codebase to the server repo. It runs after a release tag is pushed, downloads the signed and downloaded apk from artifacts , generates version.json, and pushes both to the server repository.

```yaml ( the file needs to be corrected though, tye builds instead of downloading , it was just because of earlier structure but after, i felt it's just backward 😅)
name: Push Update to Server Repo

on:
  workflow_run:
    workflows: ["Release Build"]
    types:
      - completed
  workflow_dispatch:
    inputs:
      version_name:
        description: 'Version name (e.g., 2.1.0)'
        required: true
        type: string
      version_code:
        description: 'Version code (e.g., 42)'
        required: true
        type: string
      release_notes:
        description: 'Release notes'
        required: false
        type: string
        default: 'Update released'
      forced:
        description: 'Force update (true/false)'
        required: false
        type: string
        default: 'false'

jobs:
  push-update:
    runs-on: ubuntu-latest
    if: ${{ github.event.workflow_run.conclusion == 'success' || github.event_name == 'workflow_dispatch' }}
    steps:
      - name: Checkout main repo
        uses: actions/checkout@v4
        with:
          path: main-repo

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'

      - name: Decode keystore
        run: |
          echo "${{ secrets.RELEASE_KEYSTORE_BASE64 }}" | base64 --decode > main-repo/release.keystore

      - name: Extract version info (if triggered by release)
        if: github.event.workflow_run
        id: extract-version
        run: |
          cd main-repo
          VERSION_NAME=$(grep -oP 'versionName\s*=\s*"\K[^"]+' app/build.gradle.kts)
          VERSION_CODE=$(grep -oP 'versionCode\s*=\s*\K\d+' app/build.gradle.kts)
          echo "version_name=$VERSION_NAME" >> $GITHUB_OUTPUT
          echo "version_code=$VERSION_CODE" >> $GITHUB_OUTPUT

      - name: Set version variables
        id: set-versions
        run: |
          if [ "${{ github.event_name }}" = "workflow_dispatch" ]; then
            echo "version_name=${{ github.event.inputs.version_name }}" >> $GITHUB_OUTPUT
            echo "version_code=${{ github.event.inputs.version_code }}" >> $GITHUB_OUTPUT
            echo "release_notes=${{ github.event.inputs.release_notes }}" >> $GITHUB_OUTPUT
            echo "forced=${{ github.event.inputs.forced }}" >> $GITHUB_OUTPUT
          else
            echo "version_name=${{ steps.extract-version.outputs.version_name }}" >> $GITHUB_OUTPUT
            echo "version_code=${{ steps.extract-version.outputs.version_code }}" >> $GITHUB_OUTPUT
            echo "release_notes=Version ${{ steps.extract-version.outputs.version_name }} release" >> $GITHUB_OUTPUT
            echo "forced=false" >> $GITHUB_OUTPUT
          fi

      - name: Build release APK
        run: |
          cd main-repo
          chmod +x gradlew
          ./gradlew assembleRelease \
            -Pandroid.injected.signing.store.file=release.keystore \
            -Pandroid.injected.signing.store.password=${{ secrets.KEYSTORE_PASSWORD }} \
            -Pandroid.injected.signing.key.alias=${{ secrets.KEY_ALIAS }} \
            -Pandroid.injected.signing.key.password=${{ secrets.KEY_PASSWORD }} \
            --no-daemon

      - name: Rename and prepare APK
        run: |
          cd main-repo
          mkdir -p staging/bin/
          mkdir -p staging/api/v1/
          APK_FILE="audiorouter-v${{ steps.set-versions.outputs.version_name }}.apk"
          cp app/build/outputs/apk/release/*.apk "staging/bin/$APK_FILE"

      - name: Compute SHA-256 checksum
        run: |
          cd main-repo/staging/bin/
          APK_FILE="audiorouter-v${{ steps.set-versions.outputs.version_name }}.apk"
          CHECKSUM=$(sha256sum "$APK_FILE" | cut -d' ' -f1)
          FILE_SIZE=$(stat -c%s "$APK_FILE")
          echo "checksum=$CHECKSUM" >> $GITHUB_ENV
          echo "file_size=$FILE_SIZE" >> $GITHUB_ENV

      - name: Generate version.json
        run: |
          cd main-repo/staging/api/v1/
          APK_FILE="audiorouter-v${{ steps.set-versions.outputs.version_name }}.apk"
          cat > version.json << EOF
          {
            "version": "${{ steps.set-versions.outputs.version_name }}",
            "versionCode": ${{ steps.set-versions.outputs.version_code }},
            "buildNumber": ${{ steps.set-versions.outputs.version_code }},
            "minSdkVersion": 29,
            "releaseDate": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
            "downloadUrl": "https://updates.vyzorix.com/bin/$APK_FILE",
            "checksumSha256": "${{ env.checksum }}",
            "fileSize": ${{ env.file_size }},
            "releaseNotes": "${{ steps.set-versions.outputs.release_notes }}",
            "forced": ${{ steps.set-versions.outputs.forced }},
            "changelog": []
          }
          EOF

      - name: Checkout server repo
        uses: actions/checkout@v4
        with:
          repository: your-username/vyzorix-update-server
          token: ${{ secrets.SERVER_REPO_TOKEN }}
          path: server-repo

      - name: Copy files to server repo
        run: |
          # Copy APK
          cp main-repo/staging/bin/*.apk server-repo/bin/

          # Copy version.json
          cp main-repo/staging/api/v1/version.json server-repo/api/v1/version.json

          # Update changelog (append new version)
          if [ -f server-repo/api/v1/changelog.json ]; then
            # Read existing changelog and prepend new version
            python3 -c "
          import json
          with open('server-repo/api/v1/changelog.json', 'r') as f:
              changelog = json.load(f)
          new_entry = {
              'version': '${{ steps.set-versions.outputs.version_name }}',
              'versionCode': ${{ steps.set-versions.outputs.version_code }},
              'releaseDate': '$(date -u +%Y-%m-%dT%H:%M:%SZ)',
              'changes': ['${{ steps.set-versions.outputs.release_notes }}']
          }
          changelog['versions'].insert(0, new_entry)
          with open('server-repo/api/v1/changelog.json', 'w') as f:
              json.dump(changelog, f, indent=2)
          "
          else
            cat > server-repo/api/v1/changelog.json << EOF
          {
            "versions": [
              {
                "version": "${{ steps.set-versions.outputs.version_name }}",
                "versionCode": ${{ steps.set-versions.outputs.version_code }},
                "releaseDate": "$(date -u +%Y-%m-%dT%H:%M:%SZ)",
                "changes": ["${{ steps.set-versions.outputs.release_notes }}"]
              }
            ]
          }
          EOF
          fi

      - name: Push to server repo
        run: |
          cd server-repo
          git config user.name "vyzorix-bot"
          git config user.email "bot@vyzorix.com"
          git add bin/ api/v1/
          git commit -m "Release v${{ steps.set-versions.outputs.version_name }} - $(date -u +%Y-%m-%dT%H:%M:%SZ)"
          git push origin main

      - name: Trigger Render deploy
        run: |
          curl -X POST "https://api.render.com/v1/services/${{ secrets.RENDER_SERVICE_ID }}/deploys" \
            -H "Authorization: Bearer ${{ secrets.RENDER_API_KEY }}" \
            -H "Content-Type: application/json" \
            -d '{"clearCache": "true"}'

      - name: Wait for Render deploy
        run: |
          echo "Waiting for Render deployment..."
          for i in {1..30}; do
            sleep 10
            HTTP_CODE=$(curl -s -o /dev/null -w "%{http_code}" "https://${{ secrets.RENDER_SERVICE_NAME }}.onrender.com/health")
            if [ "$HTTP_CODE" = "200" ]; then
              echo "Deployment successful!"
              exit 0
            fi
            echo "Attempt $i: HTTP $HTTP_CODE (waiting...)"
          done
          echo "Deployment may still be in progress. Check Render dashboard."
```

---

## Required Secrets (Main Repo)

| Secret | Purpose | Where to get it |
|--------|---------|-----------------|
| `RELEASE_KEYSTORE_BASE64` | Base64-encoded release keystore | Generate via `keytool`, encode via `base64 -w 0 release.keystore` |
| `KEYSTORE_PASSWORD` | Keystore password | Your own |
| `KEY_ALIAS` | Key alias name | Your own |
| `KEY_PASSWORD` | Key password | Your own |
| `SERVER_REPO_TOKEN` | GitHub PAT with push access to server repo | GitHub Settings -> Developer settings -> Personal Access Tokens |
| `RENDER_SERVICE_ID` | Render service ID for deployment trigger | Render Dashboard -> Service -> Settings |
| `RENDER_API_KEY` | Render API key for deployment trigger | Render Dashboard -> Settings -> API Keys |
| `RENDER_SERVICE_NAME` | Render service name for health check | Render Dashboard -> Service URL |

---

## Workflow Trigger Sequence

```
Developer pushes tag: git tag v2.1.0 && git push origin v2.1.0
    │
    ▼
release.yml triggers
    │
    ├── Builds signed release APK
    ├── Extracts version info
    ├── Creates GitHub Release with APK artifact
    └── Completes
    │
    ▼
push_update_bin.yml triggers (workflow_run: completed)
    │
    ├── downloads from release artifact
    ├── Computes SHA-256 checksum
    ├── Generates version.json
    ├── Checks out server repo
    ├── Copies APK to server-repo/bin/
    ├── Updates server-repo/api/v1/version.json
    ├── Updates server-repo/api/v1/changelog.json
    ├── Pushes to server repo main branch
    └── Triggers Render deploy
    │
    ▼
server-repo deploy.yml triggers (push: main)
    │
    ├── Validates APK files
    ├── Triggers Render deployment
    └── Waits for health check
    │
    ▼
Render auto-deploys (autoDeploy: true)
    │
    ├── Pulls latest from main branch
    ├── Builds Docker container
    ├── Deploys to production
    └── Health check passes
    │
    ▼
VyzorixAudioRouter daemon on device
    │
    ├── NetworkStateMonitor detects internet
    ├── UpdateChecker polls /api/v1/version
    ├── Compares versions -> UPDATE AVAILABLE
    ├── Shows notification to user
    ├── User taps "Download"
    ├── UpdateDownloader fetches APK
    ├── Verifies SHA-256 checksum
    ├── UpdateInstaller triggers ACTION_INSTALL_PACKAGE
    ├── System shows "Install this update?" dialog
    ├── User confirms -> APK installed
    ├── Daemon restarts with new version
    └── BootStateRestorer resumes from last known state
```
