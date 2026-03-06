# codelocator-pro-android

`codelocator-pro-android` is the standalone Android SDK workspace extracted from CodeLocatorPRO.

## Current Status

- Repo split is complete and independently buildable.
- JitPack build has been verified at tag `v2.1.0-alpha.4`.
- `CodeLocatorCore` now captures Compose semantics via `AccessibilityNodeInfo` and writes nodes into `WView.mComposeNodes (b5)`.
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
    implementation "com.github.git54496.codelocator-pro-android:codelocator-core:v2.1.0-alpha.4"
    // optional explicit model dependency
    implementation "com.github.git54496.codelocator-pro-android:codelocator-model:v2.1.0-alpha.4"
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

When validating only `CodeLocatorCore/CodeLocatorModel` locally (without Lancet modules), use JDK 11 and set `JITPACK=true`:

```bash
export JAVA_HOME="$(brew --prefix openjdk@11)/libexec/openjdk.jdk/Contents/Home"
export PATH="$JAVA_HOME/bin:$PATH"
export ANDROID_SDK_ROOT="$HOME/Library/Android/sdk"
export GRADLE_USER_HOME="$(pwd)/.gradle-jdk11"
export JITPACK=true
./gradlew --no-daemon :CodeLocatorModel:compileJava :CodeLocatorCore:compileDebugJavaWithJavac
```

Build and publish core/model to local Maven:

```bash
./gradlew clean :CodeLocatorModel:publishToMavenLocal :CodeLocatorCore:publishToMavenLocal -x test
```

## JitPack Notes

- This repository includes `jitpack.yml`.
- JitPack currently builds and publishes `CodeLocatorCore` and `CodeLocatorModel` only.
- Lancet modules are temporarily excluded on JitPack because upstream dependency `me.ele:lancet-base:1.0.6` is not resolvable from public repositories.
