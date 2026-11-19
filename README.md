# OpenLensVR GitHub Actions Build

This repository was previously empty. It now hosts a build-only copy of the independent Unity 6 OpenLensVR project for the OnePlus Nord CE5.

The Android APK will be produced by GitHub Actions **only after Unity licensing and Unity compilation succeed**. A workflow's existence is not proof of a working APK.

Project sources are packaged in `OpenLensVR-source.zip` and extracted by the CI workflow.

## Repository role

This is a build snapshot, not the editable source repository. The full working project is [yash-l/OpenLensVR](https://github.com/yash-l/OpenLensVR). Keep the ZIP until the existing workflow is retired or migrated; removing it now would break the build.
