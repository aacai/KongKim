# KongKim 金刚功跟练

跟着口令练完全程：八段锦 / 金刚功两套口令视频，支持画面与纯音频两种模式、倍速、±10 秒快进退、进度拖拽，以及每日定时提醒。

## 功能

- **两套口令**：五遍-version（5x）与九遍-version（9x），切换版本时自动接续进度
- **画面 / 音频模式**：只想要声音时不必点亮视频，来回切换不丢进度
- **播放控制**：±10 秒跳转、进度条拖拽、从头播放、0.75×–2× 倍速
- **每日提醒**：定时唤醒播放，支持选择提醒版本（Android 12+ 需在系统设置里允许「闹钟和定时器」）

## 技术栈

Kotlin Multiplatform + Compose Multiplatform、androidx.media3（`MediaSessionService` + `ExoPlayer` + `MediaController`，自定义 `SessionCommand` 下发播放配置）、Compose Material 3、`AlarmManager` 定时唤醒。

模块：`androidApp`（手机/平板端）、`desktopApp`（JVM 桌面端）、`shared`（跨端共享）。
视频与音频素材位于 `media/`，构建时作为 assets 挂进 APK。

## 本地构建

```bash
# Android
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:assembleRelease

# Desktop
./gradlew :desktopApp:run
```

Release 包需要签名，把参数写进 `androidApp/signing.properties`（已被 `.gitignore` 排除）：

```properties
storeFile=key.jks
storePassword=...
keyAlias=...
keyPassword=...
```

缺少该文件时 `release` 构建自动退回 debug 签名，本地开发不会中断。

## CI 打包

`.github/workflows/build.yml` 复刻了 ClipSync 的 Actions 任务：

| 触发 | 产物 |
| --- | --- |
| 推送 `main` | 签名 APK（Artifacts） |
| 推送 tag `v*` / `V*` | APK + deb / msi / dmg + GitHub Release |
| 手动 `workflow_dispatch` | 按勾选构建 |

签名材料通过仓库 Secrets 注入，不进版本库：

- `KEYSTORE_BASE64`：`base64 -i key.jks` 的输出
- `KEYSTORE_PASSWORD` / `KEY_ALIAS` / `KEY_PASSWORD`：签名口令

`media/*.mp4` 走 Git LFS（单个视频超过 GitHub 的 100MB 单文件上限），构建 Android 包时由 `actions/checkout` 的 `lfs: true` 取回真实文件。

## 素材

`media/` 下的口令音视频随仓库公开分发，请自行确认其使用授权；提示音 `message_alert_cc0.*` 来自 CC0 素材，许可证见同目录 `message_alert_cc0.LICENSE.txt`。
