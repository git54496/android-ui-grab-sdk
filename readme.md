# codelocator-pro-android

`codelocator-pro-android` is the standalone Android SDK workspace extracted from CodeLocatorPRO.

## Current Status

- Repo split is complete and independently buildable.
- JitPack build has been verified at tag `v2.1.0-alpha.3`.
- Current published artifacts on JitPack:
  - `codelocator-core` (`aar`)
  - `codelocator-model` (`jar`)

## Consume From JitPack

Add JitPack repository in your Android project:

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}
```

Add dependencies:

```groovy
dependencies {
    implementation "com.github.git54496.codelocator-pro-android:codelocator-core:v2.1.0-alpha.3"
    // optional explicit model dependency
    implementation "com.github.git54496.codelocator-pro-android:codelocator-model:v2.1.0-alpha.3"
}
```

## Modules

- `CodeLocatorModel`
- `CodeLocatorCore`
- `lancet/CodeLocatorLancet*`
- `app` sample app

## Local Build

Build all modules:

```bash
./gradlew clean build
```

Build and publish core/model to local Maven:

```bash
./gradlew clean :CodeLocatorModel:publishToMavenLocal :CodeLocatorCore:publishToMavenLocal -x test
```

## JitPack Notes

- This repository includes `jitpack.yml`.
- JitPack currently builds and publishes `CodeLocatorCore` and `CodeLocatorModel` only.
- Lancet modules are temporarily excluded on JitPack because upstream dependency `me.ele:lancet-base:1.0.6` is not resolvable from public repositories.
