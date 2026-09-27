# Contributing

Issues and pull requests are welcome. Include the device model, Android version, glasses model, steps to reproduce, and relevant app logs for bug reports.

## Build

The Android project is in `xreal-hand-mouse/`. See the [README](README.md#소스에서-빌드) for prerequisites and release build commands. Unit tests can be run with:

```powershell
cd xreal-hand-mouse
.\gradlew.bat test
```

Keep user-facing text available in both English (`res/values`) and Korean (`res/values-ko`). Voice commands should support only those two languages. Prefer focused changes that explain why behavior changed.

## Source and licensing

Do not add proprietary XREAL libraries, firmware, assets, or code copied from closed-source apps. USB protocol work must come from observation of owned hardware. Attribute compatible open-source dependencies in [`NOTICE`](NOTICE). Contributions are licensed under [Apache-2.0](LICENSE).

This is an independent project and is not affiliated with XREAL.
