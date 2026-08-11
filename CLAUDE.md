# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

LyriconProvider is a unified Xposed module that provides lyrics (逐字歌词 + 翻译) for 14 music player apps. It was consolidated from multiple standalone Xposed module APKs into a single app.

## Build Commands

```bash
# Compile check (fastest, no APK)
./gradlew :app:compileDebugKotlin

# Build debug APK
./gradlew :app:assembleDebug

# Build release APK (requires signing config)
./gradlew :app:assembleRelease
```

No test suite is configured for the app module. `compileDebugKotlin` is the primary verification step.

## Architecture

**Single `app` module** — no submodules. Everything lives under `app/src/main/kotlin/io/github/proify/lyricon/provider/`.

### Entry Point

`HookEntry.kt` — the unified Xposed entry. Uses YukihookAPI's `@InjectYukiHookWithXposed`. Contains all `loadApp(packageName, hooker)` calls for every provider. When adding a new provider, add its `loadApp()` call here.

### Package Layout

- `providers/` — one sub-package per music app (e.g., `netease/`, `qqmusic/`, `spotify/`). Each has a `Constants.kt` with `PROVIDER_PACKAGE_NAME` and an object/class extending `YukiBaseHooker`.
- `parsers/` — lyric format parsers (lrckit, krckit, qrckit, yrckit, cloudlyric). Pure logic, no Android framework dependency except cloudlyric which uses OkHttp.
- `utils/` — `android/` for Android platform helpers, `extensions/` for Kotlin extension functions.

### Key Dependencies

- **YukiHookAPI** — Xposed hook framework. KSP generates `xposed_init` from `@InjectYukiHookWithXposed`.
- **KavaRef** — reflection helper used alongside YukiHookAPI for class member resolution.
- **DexKit** — runtime dex searching for obfuscated targets.
- **TagLib** (kyant0) — reads embedded metadata from audio files (used by PowerAmp provider).
- **lyricon:provider / lyricon:lyric:model** — external SDK for the Lyricon lyrics display framework.
- **OkHttp + Brotli** — HTTP client for cloud lyric search.

### Provider Pattern

Most providers follow this pattern:
1. `Constants.kt` — `PROVIDER_PACKAGE_NAME`, target app package name(s), SVG icon
2. Main hooker object extending `YukiBaseHooker` — hooks the target app's internal APIs
3. Optional: `Downloader.kt`, cache classes, parser helpers

Some providers use `object` (singleton, e.g., `CloudMusic`, `Spotify`, `QiShui`), others use `class` with `()` in `loadApp()` (e.g., `KuGou()`, `Gramophone()`). This matches how they were originally written — don't change the pattern.

### Resources

- `AndroidManifest.xml` — Xposed module metadata (`xposedmodule`, `xposedscope`, `lyricon_module_*`)
- `res/values/arrays.xml` — `xposed_scope` must list every target app package name; `lyricon_module_tags` declares capabilities
- `res/values/strings.xml` — app name, descriptions

### Excluded Providers

The following were intentionally excluded from this unified build: `cloud-provider`, `meizu-provider`, `car-provider`. Do not add them without explicit request.

## Commit Convention

Format: `<type>: <english summary>`

The description body is written in Chinese. No `Co-Authored-By` trailers unless explicitly requested.

Types: `feat`, `fix`, `refactor`, `docs`, `chore`

Example:
```
feat: 新增 PowerAmp Provider，仅读取本地内嵌歌词

```

**Only commit after changes compile successfully AND the user approves.**

## Adding a New Provider

1. Create `app/src/main/kotlin/.../providers/<name>/` with `Constants.kt` and hooker
2. Add `loadApp(targetPackage, Hooker)` in `HookEntry.kt`
3. Add target package to `xposed_scope` array in `res/values/arrays.xml`
4. Run `./gradlew :app:compileDebugKotlin` to verify
5. Commit after user approval
