# SISU Ceiling Scan — plan-first MVP 0.2.0

Android Studio Kotlin prototype that imports a JPG/PNG/PDF architectural plan, adds a selectable and editable rectangular capture-station grid, stores original camera images under their explicitly selected grid cells, suggests the next unphotographed cell, renders a plan-linked thumbnail overview, and attempts a **conservative, local** overlap mosaic with OpenCV ORB/RANSAC. The mosaic uses a single-source seam policy (instead of alpha blending) to avoid visual doubles where possible.

**Not yet:** automatic room boundary recognition, automatic in-building camera location (SLAM), metric camera-to-plan calibration, or reliable global 20–40-photo orthomosaic over rooms. Some manually selected plan cells may include parallax; grid overview should not be taken as measured as-built documentation.

The A35's ultrawide physical camera may not be accessible to third-party apps; the camera module selects the widest exposed CameraX back camera or lowest supported zoom and falls back safely.

Delivered as source only. **Not compiled or tested on an A35** because the production environment lacks Android SDK/Gradle. Open the folder in Android Studio, sync dependencies, configure Gradle 8.9 / Android Gradle Plugin 8.7.3 as needed, run and test on hardware before operational use. The distribution does not include a Gradle wrapper executable/JAR; generate one using Gradle if required.

See `README_FA.md` for Persian workflow and constraints.
