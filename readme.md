# codelocator-pro-android

Standalone Android SDK workspace for CodeLocator PRO.

## Modules

- `CodeLocatorModel`
- `CodeLocatorCore`
- `lancet/CodeLocatorLancet*`
- `app` sample app

## Build

```bash
./gradlew clean build
```

## JitPack

This repo includes `jitpack.yml` and can be consumed from JitPack after a tag is pushed.

Current JitPack build publishes:

- `codelocator-model`
- `codelocator-core`

Lancet modules are excluded from JitPack build for now due upstream dependency resolution limits.
