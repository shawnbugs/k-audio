# K剪音 交接文档

> 写于 2026-11-06，2026-11-07 更新；版本 **0.3.0**（versionCode 3，代码已推送，CI 因 GitHub 账单问题还没编出来）。**新会话先读本文，再读 [README](../README.md)**。
> README 讲「功能现状、关键实现、构建」，本文讲「怎么接手、用户要什么、为什么这么做、还有什么没做」，两份不重复。

## 1. 一分钟上手

| 项 | 值 |
|---|---|
| 代码 | `D:\AiWork\Android\k-audio-app`，分支 `main` |
| 仓库 | https://github.com/shawnbugs/k-audio （Private） |
| 包名 | `com.shawnbugs.kaudio` |
| 来源 | PC 版 `D:\AiWork\AudioTool`（C# WinForms，`src\*.cs`），逻辑对照着它移植 |
| APK | 下载到 `apk\`（已 gitignore） |
| 用户手机 | 小米 HyperOS 1.0（M2012K11C）；adb 在 `D:\Program Files\SoftWare\scrcpy-win64-v3.3.4\adb.exe`，平时只读排查，用户要求时可装 APK |
| 兄弟项目 | k-todo、k-call、k-practice…都在 `D:\AiWork\Android\`，构建流程一样 |

发版：改代码 → `versionCode` +1、`versionName` 改 → README 功能表标版本号 → `git commit`（`K剪音 0.x.y：改了什么`）→ push →
等 Actions → 下载 APK 到 `apk\` → 告诉用户完整路径，说清哪些没在真机上验证过。gh 和 git push 都走 Clash 7897。

## 2. 和用户打交道的规矩

- 用中文回复；代码注释、提交信息也用中文。
- 只发正式版（assembleRelease）。默认用户自己装；用户说了「手机连着，直接装」时才 adb install -r（2026-11-06 0.1.0 就是这样装的）。
- 作者署名「司徒王允」（`ui/App.kt` 的 `AUTHOR`，「关于」弹框）。
- **风格不再照 K功课**（用户 2026-11-06 明确要求换掉）。0.2.0 起是 K剪音自己的深色「录音棚」风：墨黑底、薄荷青 `#36D6B0` 主色、
  紫 `#9B8CFF` 第二色；图标是「两段声波 + 中间白色斜刀口」（左青右紫）。配色都在 `ui/Design.kt`，只有深色一套。

## 3. 关键决定和原因

| 决定 | 原因 |
|---|---|
| **CI 自编 ffmpeg**（不用系统 MediaCodec） | 用户选的。保住 PC 版全部格式（WMA/APE 输入，MP3/Vorbis/WMA 输出，「保持原格式」无损复制），导出滤镜逐字搬。代价是 APK 多约 10 MB |
| 不用 FFmpegKit | 2025 年已停更、Maven 上的包下架了 |
| ffmpeg 当可执行文件跑（`libffmpeg.so` + `useLegacyPackaging`） | Android 10 起 App 私有目录不能 exec，只有 nativeLibraryDir 可以；这样 PC 版「拼命令行调 ffmpeg」的写法原样保留 |
| 只编 arm64、minSdk 29 | 用户和身边的手机都是 arm64；29 起 MediaStore 写「音乐」不要权限 |
| 输入先复制到缓存 | ffmpeg 读不了 content:// URI；mp4 之类要能 seek，不能走管道 |
| 输出固定 `Music/K剪音` | 手机上拿不到源文件真实目录；MediaStore 是不要权限的正路 |
| 视频的「保持原格式」换音频扩展名 | 「音乐」集合只收 audio/* MIME；mkv 兜底（什么编码都能装） |
| 导出走前台服务 | HyperOS 后台杀得狠，批量导出几分钟会被杀 |
| 列表不跨启动保留 | 同 PC 版；也省得缓存里的副本越积越多 |

## 4. 没做完 / 没验证的

- **当前进度（2026-11-07）**：用户要求把 PC 版 v4 的视频剪辑移植过来（PC 版交接在 `D:\AiWork\AudioTool\HANDOFF.md`）。
  用户定的范围：视频导出 H.264 + H.265 + 无损快剪三种都做，画面预览照 PC 版（播放头一帧、拖边线看边线帧、逐帧、全屏）。
  **0.3.0 代码已推送**（`7c1fca6` ffmpeg 加 x264/x265/bmp，下一个提交是 App 代码），但 GitHub Actions 从这次起报
  「recent account payments have failed or your spending limit needs to be increased」，任务根本没启动（startup_failure）。
  查下来不是额度（11 月只用 0.34/12 美元），是账号的账单信息被 GitHub 按贸易管制判为不合格，私有仓库 Actions 被锁，
  用户 2026-11-07 已向 GitHub Support 提交工单（单位是误填），等回复；或把仓库改公开、或换地方编译——
  **代码还没编译过，ffmpeg 新脚本也没跑过**。用户处理好账单后：`gh workflow run android.yml -R shawnbugs/k-audio --ref main`，
  看 ffmpeg job 能否过（x265 的 NEON 汇编编不过会自动退纯 C，日志里有「!!!!」提示），再修 Kotlin 编译错误。
  验证重点：取画面速度（每帧起一次 ffmpeg）、H.265 在手机上的导出速度、无声视频、导出到 Movies/K剪音 相册能否看到。
- 之前（2026-11-06）：0.1.0（暖色，照 K功课）出过一版后，用户要求换风格；**0.2.0 深色录音棚风**
  CI 绿灯（run `42173807066`），`apk\KAudio-0.2.0.apk`（约 32.7 MB，含 19.2 MB `libffmpeg.so`）已 adb 装到用户手机。
  新配色和图标还没在手机上看过效果，功能也还没真机验证，等用户反馈。
  教训：Kotlin 注释里别写斜杠星号（`audio/*` 会被当成嵌套注释开头）。
- APK 里 `classes.dex` + `classes2.dex` 共约 22 MB，看着像 release 没开 R8 压缩（`isMinifyEnabled`）；以后可以开，开之前要配好 keep 规则再上真机验证。

- 0.1.0 **还没在真机上跑过**（首版）。重点验证：ffmpeg 能否执行（关于里没报「找不到内置的 ffmpeg」）、长文件解码、触摸框选手感、双指缩放、导出后在「音乐」里能看到、通知栏进度和停止。
- 试听临时 WAV 是 44.1kHz 立体声，1 小时音频约 635 MB 缓存；超长文件可能撑爆小存储手机。真遇到再考虑降采样率或分段。
- 选区拖到屏幕边缘不会自动滚动（PC 版也没有）。
- 没做录音：用户没提，别主动加。（视频剪辑 2026-11-07 用户明确要了，0.3.0 在做；画面区域裁剪 / 旋转之类 PC 版也没有，用户没提别加。）
