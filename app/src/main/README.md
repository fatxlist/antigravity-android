# main

## Overview
Primary Android application source set containing `AndroidManifest.xml` (declaring `minSdkVersion="24"`, `targetSdkVersion="28"` for Android 10+ W^X `exec` compatibility, signature-level `com.agy.permission.INTERNAL_BROADCAST`, `MainActivity`, `LauncherDarkActivity`, `LauncherLightActivity`, `ShareReceiverActivity`, `TorchReceiver`, `ApprovalActionReceiver`, `CoreServerService`, `VoiceForegroundService`, and `AppFileProvider`), Android XML/drawable/mipmap/raw resources under `res/`, and the Java source tree under `java/`.

## Subdirectories & Boundaries
- `java`: [./java/README.md](./java/README.md) - Production Java source tree for `com.agy.*`.
