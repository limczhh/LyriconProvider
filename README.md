<!--suppress ALL -->

# LyriconProvider - 词幕歌词提供器（大杂烩版）

#### 基于 Xposed 的歌词提供器

> **本项目基于 [tomakino/LyricProvider](https://github.com/tomakino/LyricProvider) 进行修改。**
>
> 主要变更：
> - 将多个独立子模块合并为单一 APP，统一入口
> - 移除了 cloud-provider、meizu-provider、car-provider 模块
> - 魔改 PowerAmp Provider：移除设置页面，默认开启翻译，仅读取本地内嵌歌词
> - 统一项目结构，合并 parsers/utils 到单一模块内

![Platform](https://img.shields.io/badge/Platform-Android-brightgreen?style=flat&logo=android)
![License](https://img.shields.io/github/license/limczhh/LyriconProvider?style=flat)

## 支持平台

| 平台名称 | 包名 | 功能说明 |
|:---|:---|:---|
| **Apple Music** | `com.apple.android.music` | 支持逐字、翻译、背景人声、对唱格式歌词 |
| **网易云音乐 / 荣耀版** | `com.netease.cloudmusic` / `com.hihonor.cloudmusic` | 支持逐字歌词、翻译歌词 |
| **QQ 音乐** | `com.tencent.qqmusic` | 支持逐字歌词、翻译歌词 |
| **QQ 音乐 HD** | `com.tencent.minihd.qqmusic` | 支持逐字歌词、翻译歌词 |
| **LX 音乐** | `cn.toside.music.mobile` | 支持翻译歌词显示 |
| **酷狗音乐 / 概念版** | `com.kugou.android` / `com.kugou.android.lite` | **需在 App 内开启车载歌词模式** |
| **酷我音乐** | `cn.kuwo.player` | **需在 App 内开启车载歌词模式** |
| **Spotify** | `com.spotify.music` | 目前仅支持标准歌词 |
| **PowerAmp** | `com.maxmpz.audioplayer` | 仅读取本地内嵌歌词，默认开启翻译 |
| **Salt 音乐** | `com.salt.music` | 基于魅族标准歌词接口适配 |
| **汽水音乐** | `com.luna.music` | 支持动态歌词、翻译歌词 |
| **MusicFree** | `fun.upup.musicfree` | 支持翻译 |
| **Symfonium** | `app.symfonik.music.player` | 支持逐字歌词 |
| **Gramophone** | `org.akanework.gramophone` | 支持逐字歌词 |

---

## 快速安装

1. **下载**：前往 [Releases 页面](https://github.com/limczhh/LyriconProvider/releases) 获取最新的 APK 安装包。
2. **激活**：安装后进入 **LSPosed 管理器**，勾选启用 **Lyricon Provider**。
3. **配置作用域**：在 LSPosed 中勾选你需要获取歌词的音乐 App。
4. **生效**：强行停止并重新打开对应的音乐 App 即可体验。

---

## 开发者指南

请阅读原项目的 [开发文档](https://tomakino.github.io/lyricon/zh-cn/developer/provider/)

---

## 致谢

- [tomakino/LyricProvider](https://github.com/tomakino/LyricProvider) — 原始项目
