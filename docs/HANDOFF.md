# K剪音 交接文档

> 写于 2026-11-06，2026-11-09 更新；版本 **0.5.0**（versionCode 5；GitHub Actions 因账号被锁跑不了，2026-11-09 在本地 WSL 编出 `apk\KAudio-0.5.0.apk`，11-09 已 adb install -r 装到用户手机（覆盖 0.4.0），等反馈）。**新会话先读本文，再读 [README](../README.md)**。
> README 讲「功能现状、关键实现、构建」，本文讲「怎么接手、用户要什么、为什么这么做、还有什么没做」，两份不重复。

## 1. 一分钟上手

| 项 | 值 |
|---|---|
| 代码 | `D:\AiWork\Android\k-audio-app`，分支 `main` |
| 仓库 | https://github.com/shawnbugs/k-audio （2026-11-09 起公开，别提交敏感信息） |
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

- **0.5.1（2026-11-09）换图标，已编出 `apk\KAudio-0.5.1.apk`（versionCode 6）**：用户先看了 3 个剪刀候选，没选，发了 CapCut / 剪映的图标说「模仿这个改下」。
  做法：照它的风格（黑底、粗白线条图形、青 / 红两道错开的重影），图形换成自己的剪刀（两个圈把手 + 交叉刀刃），重影用薄荷青 + 紫；
  **没照搬 CapCut 的标志**（字节跳动的商标，仓库又是公开的）。前景三层同一路径、只平移不同；背景纯 `#0B0D12`；单色图标和通知栏小图标是同款剪刀。
  手机上的启动器效果还没看过。以下是之前的需求记录：
- 换图标需求（2026-11-09 用户追加）。用户原话「顺便把图标也换了 图标太丑了」，要求跟 0.5.0 一起出。
  0.5.0 已装到手机（用户说「直接装」，图标还是旧的），所以换完图标要升 0.5.1（versionCode 6）再出包。
  文件在 `app/src/main/res/drawable/`：`ic_launcher.xml`（自适应图标）、`ic_launcher_background.xml`、`ic_launcher_foreground.xml`、
  `ic_launcher_monochrome.xml`（单色主题图标，要同步改），通知栏小图标 `ic_stat.xml` 看情况跟着换。现在的图案是 0.2.0 画的「两段声波 + 中间白色斜刀口，左青右紫」。
  配色守 K剪音自己的深色录音棚风（墨黑底、薄荷青 `#36D6B0`、紫 `#9B8CFF`，见 `ui/Design.kt`），**不要照 K功课**。
  用户补充的风格（原话）：「简洁一点 具体你设计吧 类似剪刀那种的」——图案简洁、**以剪刀为主体**（可以带一点声波 / 音轨的意思，别堆元素），具体由我们设计。
  做法：先画 3~4 个剪刀款候选，渲染成图片给用户挑（Python 把 vector 路径画成 PNG，或 HTML 预览页），用户选定再改 xml；
  自适应图标前景要留安全区（108dp 画布里中间 66dp 左右）。选定的图标写进本文和 README。
- **当前进度（2026-11-09）0.5.0**：用户问「两个视频合并成一个功能在哪」——0.4.0 的「合并成一个」只合并声音，用户要的是画面加声音一起合并。
  做法：「合并成一个」在格式选视频格式时走 `Studio.runMergeVideo`（音频格式仍走 0.4.0 的路径，行为不变）。一条 ffmpeg 命令多个输入：
  没剪 / 只截一段的用输入端 -ss/-to，删了中间或加了效果的套 `buildFilter`（input/tag，统一尺寸当 vpost 放在淡入淡出前）；
  每段 `scale=W:H:force_original_aspect_ratio=decrease,pad=…居中,setsar=1,fps=…,format=yuv420p`，W×H 照第一个视频（选了分辨率按所选只缩不放），
  帧率照第一个（`Ffmpeg.fpsArg`：29.97 写成分数，接近整数的取整）；声音 aformat 成 48k 立体声，没声音的视频用滤镜里的
  `anullsrc` + atrim 补等长静音（**anullsrc / aevalsrc / testsrc2 / sine 这些滤镜都编进去了，没有的只是 lavfi 设备**，`-f lavfi` 不能用、
  `-filter_complex` 里能用）；最后 concat v=1:a=1，编码参数和单个视频导出共用（`videoCodecArgs`）。「保留 / 去掉声音」照样生效。
  无损快剪：都没剪、`Ffmpeg.videoKey`（编码 / 像素格式 / 尺寸 / 帧率 / 旋转）和 `audioKey`（编码 / 采样率 / 声道）都一样时用 concat 分离器 `-c copy`，
  否则日志写原因、改 H.264 高画质（CRF 18、不缩放）。纯音频文件开始时 toast 提示、日志里写跳过。
  `Ffmpeg.probe` 现在会读第一条视频流的 `displaymatrix: rotation of 90 degrees`，在视频描述后面加 `, rotate=90`（ffmpeg 滤镜会自动转正，所以尺寸要对调）。
  合并命令已在手机上用同一个 ffmpeg 跑通（libffmpeg.so 拷到 /data/local/tmp，testsrc2 + sine 生成测试视频，测完已删）：
  ① H.264：横屏有声 + 4:3 无声且截一段 + 竖屏单声道 44.1k 删中间加音量淡入 + 带旋转标记的，10.05 秒、无声段 -91 dB、
  4:3 / 竖屏 / 转正后的都左右补黑边居中；② H.265 480p 去掉声音、竖屏在前 → 480x852，横屏上下补黑边；③ 无损快剪格式一样 → 直接拼 6.02 秒；
  ④ 无损快剪旋转不同 → H.264 重新编码。**App 里的界面操作没在真机上点过**；真手机录的视频（可变帧率、HDR / 10bit、
  不同手机拍的）合并也没试过。0.5.0 APK 编出来了（Kotlin 没有新警告），11-09 已 adb install -r 装到用户手机（覆盖 0.4.0）。
- **0.4.0（2026-11-09）**：用户装上 0.3.0 后要了两个功能——**音视频分离**（用户明确说：就是把画面和声音拆成两个文件，
  **不是 AI 人声分离**，别做复杂）和**多个音频合并成一个**。做法：格式表加「音视频分离」（`FormatInfo.split`；两半分别借用
  「视频 无损快剪 + 去掉声音」和「保持原格式 (音频)」两条现成导出路径），底部栏加「合并成一个」（`Studio.runMerge`：一条 ffmpeg 命令
  多个输入，每个文件 `buildFilter` 带 input/tag 套自己的剪辑，再 aformat 统一采样率声道后 concat；只支持具体音频格式）。
  合并顺序 = 列表添加顺序，**没做拖动排序**（用户没提；真要再加）。11-09 已装到用户手机（adb install -r，覆盖 0.3.0）。
  合并的滤镜命令已在手机上用同一个 ffmpeg 跑通（libffmpeg.so 推到 /data/local/tmp + Python 生成的 WAV，测完已删）：
  未剪辑→mp3、剪两段+音量+淡入淡出→opus（自动转 48k）、单声道 22050→wav 三种，时长都对。
  **App 里的界面操作、音视频分离、0.3.0 的视频功能都还没在真机上点过**，等用户反馈。
- **0.3.0（2026-11-07）**：用户要求把 PC 版 v4 的视频剪辑移植过来（PC 版交接在 `D:\AiWork\AudioTool\HANDOFF.md`）。
  用户定的范围：视频导出 H.264 + H.265 + 无损快剪三种都做，画面预览照 PC 版（播放头一帧、拖边线看边线帧、逐帧、全屏）。
  **0.3.0 代码已推送**（`7c1fca6` ffmpeg 加 x264/x265/bmp，下一个提交是 App 代码），但 GitHub Actions 从这次起报
  「recent account payments have failed or your spending limit needs to be increased」，任务根本没启动（startup_failure）。
  查下来不是额度（11 月只用 0.34/12 美元），是账号的账单信息被 GitHub 按贸易管制判为不合格，私有仓库 Actions 被锁，
  用户 2026-11-07 已向 GitHub Support 提交工单（单位是误填），等回复；11-08 重跑仍 startup_failure；
  11-09 发现「预算和提醒」里 Actions 等 5 项预算都是 $0 且 Usage blocked，想改成 $1 要先绑卡、绑卡又被贸易管制挡回，
  改不了，别再让用户试。11-09 用户把仓库改成公开试过，**公开仓库也跑不了**（job 注释「your account is locked due to a billing issue」），
  建议改回私有（但官方说受限账号公开后可能改不回私有）。被锁根源大概率是：用户以前让别人代办 GitHub 学生认证（为免费 Copilot），
  学校填了北京大学（美国实体清单）；已建议用户在工单里照实补充说明、请客服去掉这个学校关联，**别改填别的假学校**。
  **2026-11-09 起本地 WSL 打包**（见下面「本地打包」），**0.3.0 已在本地编出来**：ffmpeg（含 x264/x265，x265 的 NEON 汇编编过了、
  没退纯 C；NEEDED 只有 libm/libz/libdl/libc）+ Kotlin 一次编过（只有 4 个警告，没改源码）。APK 约 34.7 MB（`libffmpeg.so` 23.6 MB），
  签名与 0.2.0 相同，**11-09 已 adb install -r 装到用户手机**（覆盖 0.2.0），等用户反馈。GitHub 解锁后：`gh workflow run android.yml -R shawnbugs/k-audio --ref main` 确认 CI 也能过。
  **全部视频功能都还没在真机上验证**。验证重点：取画面速度（每帧起一次 ffmpeg）、逐帧 / 全屏、H.264 / H.265 / 无损快剪导出
  （H.265 在手机上的速度）、无声视频、导出到 Movies/K剪音 相册能否看到；以及 0.2.0 起没验证的音频功能（见下）。
- **本地打包（2026-11-09 搭，GitHub 解锁前的主路线）**：WSL `Ubuntu-24.04`（磁盘 `F:\WSL\Ubuntu-24.04\ext4.vhdx`），
  `C:\Users\Administrator\.wslconfig` 设了 `networkingMode=mirrored`（WSL 才能用 Clash 7897）+ `autoMemoryReclaim=gradual`。
  三个脚本在 `tools/`：`wsl-root-packages.sh`（root 装 apt 包，含 JDK 17、gcc）→ `wsl-setup.sh`（~/android-sdk：android-36、
  build-tools 35、NDK r30；~/.gradle 代理）→ `wsl-build.sh`（同步到 ~/build/k-audio、按 build.sh 哈希缓存 ffmpeg、assembleRelease、
  APK 复制到 `apk\KAudio-<版本>.apk`）。用法：`wsl -d Ubuntu-24.04 -- bash /mnt/d/AiWork/Android/k-audio-app/tools/wsl-build.sh`，
  日志重定向到 ~/build.log 再 grep。用户要求：不用时 `wsl --shutdown` 关掉；GitHub 解封后本地环境停用。
  **没开稀疏 VHD**（WSL 提示该功能可能损坏数据，已关回去）。脚本已提交（`4a39a36`）。
  网络坑（11-09 实测）：经 Clash 拉 archive.ubuntu.com 只有约 2 KB/s（apt 卡死），拉 Google / Maven Central 时不时
  「Remote host terminated the handshake」整次构建失败；阿里云镜像直连快得多。所以 `wsl-root-packages.sh` 改成从阿里云下 .deb
  （不改系统软件源），`wsl-setup.sh` 写 `~/.gradle/init.d/aliyun-mirror.gradle`（阿里云镜像排前面、原仓库兜底，nonProxyHosts 加了阿里云）。
  11-09 编出 0.3.0：ffmpeg 首次连依赖库约 15 分钟，之后按 build.sh 哈希走缓存（`~/build/ffmpeg-cache`）；Gradle 首次约 7 分钟。
  看结果以日志里出现 `OK -> …/apk/KAudio-<版本>.apk` 为准（ffmpeg 那步也会打一行 `OK -> …/out/ffmpeg`，别看错）。
  在 PowerShell 里调 `wsl … bash -c "…; echo exit=\$?"` 时 `$?` 会被 PowerShell 或 wsl 外层 shell 先展开，记下的 exit 不可信。
- 之前（2026-11-06）：0.1.0（暖色，照 K功课）出过一版后，用户要求换风格；**0.2.0 深色录音棚风**
  CI 绿灯（run `42173807066`），`apk\KAudio-0.2.0.apk`（约 32.7 MB，含 19.2 MB `libffmpeg.so`）已 adb 装到用户手机。
  新配色和图标还没在手机上看过效果，功能也还没真机验证，等用户反馈。
  教训：Kotlin 注释里别写斜杠星号（`audio/*` 会被当成嵌套注释开头）。
- APK 里 `classes.dex` + `classes2.dex` 共约 22 MB，看着像 release 没开 R8 压缩（`isMinifyEnabled`）；以后可以开，开之前要配好 keep 规则再上真机验证。

- 0.1.0 **还没在真机上跑过**（首版）。重点验证：ffmpeg 能否执行（关于里没报「找不到内置的 ffmpeg」）、长文件解码、触摸框选手感、双指缩放、导出后在「音乐」里能看到、通知栏进度和停止。
- 试听临时 WAV 是 44.1kHz 立体声，1 小时音频约 635 MB 缓存；超长文件可能撑爆小存储手机。真遇到再考虑降采样率或分段。
- 选区拖到屏幕边缘不会自动滚动（PC 版也没有）。
- 没做录音：用户没提，别主动加。（视频剪辑 2026-11-07 用户明确要了，0.3.0 在做；画面区域裁剪 / 旋转之类 PC 版也没有，用户没提别加。）
