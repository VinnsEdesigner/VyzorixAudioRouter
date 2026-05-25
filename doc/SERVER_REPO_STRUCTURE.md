# Vyzorix Update Server — Repository Structure

```
vyzorix-update-server/
│
├── README.md                              # Server documentation, setup instructions
├── LICENSE                                # Repository license
├── .gitignore                             # Ignore node_modules, logs, env files
├── package.json                           # Express.js dependencies
├── package-lock.json                      # Locked dependency versions
├── server.js                              # Express.js API server
├── Dockerfile                             # Container definition for Render
├── render.yaml                            # Render deployment configuration
├── .env.example                           # Environment variables template
│
├── bin/                                   # APK Binary Storage (populated by CI/CD)
│   ├── audiorouter-v2.0.0.apk             # Initial release APK
│   ├── audiorouter-v2.1.0.apk             # Updated release APK
│   └── audiorouter-v2.2.0.apk             # Future release APK
│
├── api/
│   └── v1/
│       ├── version.json                   # Current version metadata (updated by CI/CD)
│       └── changelog.json                 # Historical changelog data (updated by CI/CD)
│
├── public/
│   ├── index.html                         # Simple landing page with server status
│   ├── style.css                          # Minimal styling for landing page
│   └── health.json                        # Static health check file (fallback)
│
├── scripts/
│   ├── generate_version.sh                # Helper script to generate version.json
│   ├── compute_checksum.sh                # Helper script to compute SHA-256
│   └── validate_apk.sh                    # Helper script to validate APK before push
│
├── .github/
│   └── workflows/
│       └── deploy.yml                     # Auto-deploy to Render on main branch push
│
└── logs/
    └── .gitkeep                           # Ensures logs directory exists in git
```

---

## File Descriptions

### `package.json`
```json
{
  "name": "vyzorix-update-server",
  "version": "1.0.0",
  "description": "Static update server for VyzorixAudioRouter APK distribution",
  "main": "server.js",
  "scripts": {
    "start": "node server.js",
    "dev": "nodemon server.js",
    "health": "curl http://localhost:3000/health"
  },
  "dependencies": {
    "express": "^4.18.2",
    "cors": "^2.8.5",
    "helmet": "^7.1.0",
    "compression": "^1.7.4"
  },
  "devDependencies": {
    "nodemon": "^3.0.2"
  },
  "engines": {
    "node": ">=18.0.0"
  }
}
```

### `server.js`
```javascript
const express = require('express');
const path = require('path');
const cors = require('cors');
const helmet = require('helmet');
const compression = require('compression');
const fs = require('fs');

const app = express();
const PORT = process.env.PORT || 3000;

// Security middleware
app.use(helmet({
  contentSecurityPolicy: false, // Allow app to fetch
  crossOriginResourcePolicy: { policy: 'cross-origin' }
}));
app.use(compression());
app.use(cors({
  origin: ['android-app://com.vyzorix.audiorouter'],
  methods: ['GET', 'HEAD'],
  allowedHeaders: ['Accept', 'X-App-Version', 'X-App-Build', 'X-Device-Model', 'X-Android-Version', 'Range']
}));

// Serve static files
app.use('/bin', express.static(path.join(__dirname, 'bin'), {
  setHeaders: (res, filePath) => {
    if (filePath.endsWith('.apk')) {
      res.setHeader('Content-Type', 'application/vnd.android.package-archive');
      res.setHeader('Accept-Ranges', 'bytes');
      res.setHeader('Cache-Control', 'public, max-age=3600');
    }
  }
}));

app.use('/api', express.static(path.join(__dirname, 'api'), {
  setHeaders: (res) => {
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Cache-Control', 'no-cache');
  }
}));

app.use(express.static(path.join(__dirname, 'public')));

// Version check endpoint
app.get('/api/v1/version', (req, res) => {
  const versionPath = path.join(__dirname, 'api', 'v1', 'version.json');
  if (fs.existsSync(versionPath)) {
    res.json(JSON.parse(fs.readFileSync(versionPath, 'utf8')));
  } else {
    res.status(404).json({ error: 'Version info not found' });
  }
});

// Changelog endpoint
app.get('/api/v1/changelog', (req, res) => {
  const changelogPath = path.join(__dirname, 'api', 'v1', 'changelog.json');
  if (fs.existsSync(changelogPath)) {
    res.json(JSON.parse(fs.readFileSync(changelogPath, 'utf8')));
  } else {
    res.status(404).json({ error: 'Changelog not found' });
  }
});

// APK download with range support (resume)
app.get('/bin/:filename', (req, res) => {
  const filename = req.params.filename;
  if (!filename.endsWith('.apk')) {
    return res.status(403).json({ error: 'Invalid file type' });
  }

  const filePath = path.join(__dirname, 'bin', filename);
  if (!fs.existsSync(filePath)) {
    return res.status(404).json({ error: 'APK not found', version: filename });
  }

  const stat = fs.statSync(filePath);
  const fileSize = stat.size;
  const range = req.headers.range;

  if (range) {
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
  res.json({
    status: 'ok',
    timestamp: new Date().toISOString(),
    uptime: process.uptime(),
    version: '1.0.0'
  });
});

// Error handling
app.use((err, req, res, next) => {
  console.error('Server error:', err.message);
  res.status(500).json({ error: 'Internal server error' });
});

app.listen(PORT, () => {
  console.log(`Vyzorix Update Server running on port ${PORT}`);
  console.log(`Health check: http://localhost:${PORT}/health`);
});
```

### `Dockerfile`
```dockerfile
FROM node:18-alpine AS builder

WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production

FROM node:18-alpine

WORKDIR /app
COPY --from=builder /app/node_modules ./node_modules
COPY package*.json server.js ./
COPY bin/ ./bin/
COPY api/ ./api/
COPY public/ ./public/

RUN addgroup -g 1001 -S nodejs && \
    adduser -S nodejs -u 1001 && \
    chown -R nodejs:nodejs /app

USER nodejs

EXPOSE 3000

HEALTHCHECK --interval=30s --timeout=3s --start-period=5s --retries=3 \
  CMD wget -qO- http://localhost:3000/health || exit 1

CMD ["node", "server.js"]
```

### `render.yaml`
```yaml
services:
  - type: web
    name: vyxorix-update-server
    env: docker
    region: oregon
    plan: free
    healthCheckPath: /health
    autoDeploy: true
    envVars:
      - key: NODE_ENV
        value: production
      - key: PORT
        value: 3000
    domains:
      - updates.vyzorix.com
```

### `.github/workflows/deploy.yml`
```yaml
name: Deploy to Render

on:
  push:
    branches: [main]
  workflow_dispatch:

jobs:
  deploy:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Validate APK files
        run: |
          for apk in bin/*.apk; do
            if [ -f "$apk" ]; then
              echo "Validating: $apk"
              file "$apk" | grep -q "Android application" || {
                echo "ERROR: $apk is not a valid APK"
                exit 1
              }
              sha256sum "$apk"
            fi
          done

      - name: Trigger Render Deploy
        run: |
          curl -X POST "https://api.render.com/v1/services/${{ secrets.RENDER_SERVICE_ID }}/deploys" \
            -H "Authorization: Bearer ${{ secrets.RENDER_API_KEY }}" \
            -H "Content-Type: application/json" \
            -d '{"clearCache": "true"}'

      - name: Wait for Deploy
        run: |
          echo "Waiting for Render deployment to complete..."
          sleep 30
          curl -f "https://${{ secrets.RENDER_SERVICE_NAME }}.onrender.com/health" || {
            echo "Health check failed after deployment"
            exit 1
          }
          echo "Deployment successful!"
```

### `scripts/generate_version.sh`
```bash
#!/bin/bash
# Generates version.json from APK metadata

APK_PATH="${1:-bin/latest.apk}"
VERSION_NAME="${2:-1.0.0}"
VERSION_CODE="${3:-1}"
RELEASE_NOTES="${4:-Update released}"
FORCED="${5:-false}"

if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: APK not found at $APK_PATH"
  exit 1
fi

CHECKSUM=$(sha256sum "$APK_PATH" | cut -d' ' -f1)
FILE_SIZE=$(stat -c%s "$APK_PATH")
FILENAME=$(basename "$APK_PATH")
RELEASE_DATE=$(date -u +%Y-%m-%dT%H:%M:%SZ)
DOWNLOAD_URL="https://updates.vyzorix.com/bin/$FILENAME"

cat > api/v1/version.json << EOF
{
  "version": "$VERSION_NAME",
  "versionCode": $VERSION_CODE,
  "buildNumber": $VERSION_CODE,
  "minSdkVersion": 29,
  "releaseDate": "$RELEASE_DATE",
  "downloadUrl": "$DOWNLOAD_URL",
  "checksumSha256": "$CHECKSUM",
  "fileSize": $FILE_SIZE,
  "releaseNotes": "$RELEASE_NOTES",
  "forced": $FORCED,
  "changelog": []
}
EOF

echo "Generated api/v1/version.json for $FILENAME"
```

### `scripts/compute_checksum.sh`
```bash
#!/bin/bash
# Computes SHA-256 checksum for an APK file

APK_PATH="${1:-bin/latest.apk}"

if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: APK not found at $APK_PATH"
  exit 1
fi

CHECKSUM=$(sha256sum "$APK_PATH" | cut -d' ' -f1)
echo "$CHECKSUM"
```

### `scripts/validate_apk.sh`
```bash
#!/bin/bash
# Validates APK file before pushing to server

APK_PATH="$1"

if [ -z "$APK_PATH" ]; then
  echo "Usage: ./validate_apk.sh <path-to-apk>"
  exit 1
fi

echo "Validating APK: $APK_PATH"

# Check file exists
if [ ! -f "$APK_PATH" ]; then
  echo "ERROR: File not found"
  exit 1
fi

# Check file type
FILE_TYPE=$(file "$APK_PATH")
if ! echo "$FILE_TYPE" | grep -q "Android application"; then
  echo "ERROR: Not a valid APK file"
  exit 1
fi

# Check file size (should be > 1MB for a real APK)
FILE_SIZE=$(stat -c%s "$APK_PATH")
if [ "$FILE_SIZE" -lt 1048576 ]; then
  echo "WARNING: APK is unusually small ($FILE_SIZE bytes)"
fi

# Compute checksum
CHECKSUM=$(sha256sum "$APK_PATH" | cut -d' ' -f1)
echo "SHA-256: $CHECKSUM"

echo "APK validation passed"
exit 0
```

### `.env.example`
```
# Vyzorix Update Server Environment Variables

# Server configuration
PORT=3000
NODE_ENV=production

# Render service (auto-populated by Render dashboard)
RENDER_SERVICE_ID=your-render-service-id
RENDER_API_KEY=your-render-api-key
RENDER_SERVICE_NAME=vyxorix-update-server

# Optional: Enable debug logging
DEBUG=false
```