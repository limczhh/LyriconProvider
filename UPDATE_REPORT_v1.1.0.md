# LyriconProvider v1.1.0 更新报告

发布日期：2026-11-05

## 新增适配

- 新增 MusicOne 支持，目标包名为 `io.github.shuyunr.musicone`。
- 通过 Android Framework `MediaSession` 获取当前歌曲、歌手、专辑、时长和播放状态，避免依赖 MusicOne 发布版中可能被混淆的内部类名。
- 使用项目现有的 QQ 音乐与 LRCLIB 云歌词源匹配歌词。
- 优先选择曲名和歌手匹配且包含中文翻译的 QQ QRC 歌词；没有翻译资源时回退到匹配的原文歌词。
- 支持逐字歌词、翻译歌词以及切歌时取消过期搜索，避免上一首歌词覆盖当前歌曲。

## 模块变更

- 将 MusicOne 加入 LSPosed 默认作用域和应用内作用域列表。
- Provider 数量由 15 个增加到 16 个。
- 保留此前的 Melodist 与 OriginOS 伪装包名识别支持。

## 验证结果

- `:app:compileDebugKotlin` 编译通过。
- Release 构建、R8 混淆和资源压缩通过。
- 通用 APK 使用本地 RSA 4096 位证书签名，并通过 Android `apksigner` 校验。
- APK 最低支持 Android 9（API 28），目标 SDK 37。

## 安装方法

1. 从本版本的 GitHub Release 下载通用签名 APK。
2. 安装或覆盖安装后，在 LSPosed 中启用 LyriconProvider。
3. 勾选 `系统界面 / System UI` 与 MusicOne。
4. 重启手机，使模块重新注入相关进程。

## 已知限制

- 翻译依赖 QQ 音乐歌词源是否收录对应歌曲的中文翻译；未收录时只显示原文。
- 当前完成了源码编译、Release 构建与签名静态验证，实际歌词效果仍受歌曲元数据和在线歌词源可用性影响。
