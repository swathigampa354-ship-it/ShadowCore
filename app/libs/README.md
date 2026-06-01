# BlackBox AAR Library

Place the `BCore-release.aar` file here to enable real virtual environment support.

## How to get the AAR

### Option A: Build from source (recommended)
```bash
git clone https://github.com/ALEX5402/NewBlackbox.git
cd NewBlackbox
# Open in Android Studio, sync Gradle
# Build → Build APK (this also generates the AAR)
# Find the AAR in: BCore/build/outputs/aar/BCore-release.aar
# Copy it here
```

### Option B: Download from GitHub Releases
1. Go to https://github.com/ALEX5402/NewBlackbox/releases
2. Download the latest `BCore-release.aar`
3. Place it in this directory

## After adding the AAR
1. Run `./gradlew assembleDebug` — the engine will auto-detect the AAR
2. The app will use `BlackBoxContainerEngine` instead of `StubContainerEngine`
3. Check logcat for `EngineModule: BlackBox SDK detected — using real engine`

## Requirements
- NDK version 29.0.13846066
- CMake (if building from source)
- Target device: arm64-v8a architecture
