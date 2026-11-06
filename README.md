# K剪音

PC 版「音频转换剪辑工具」（`D:\AiWork\AudioTool`，WinForms + ffmpeg.exe）的安卓版。
剪掉不要的部分、调音量、加淡入淡出，再转成想要的格式。自用侧载，不联网。

## 功能（0.2.0）

| 功能 | 说明 |
|---|---|
| 外观（0.2.0） | 深色「录音棚」风：墨黑底、薄荷青主色、紫色标淡入淡出；图标是「一刀切开的声波」 |
| 添加文件 | 系统文件选择器多选；也能从文件管理 / 微信「分享」或「用其他应用打开」进来。音频、视频都行（视频只取声音） |
| 波形 | 单指拖动框选；拖选区两边的圆柄调整；点一下定位播放头；双击选中两个剪辑点之间的整段；双指缩放平移；底部概览条拖动平移 |
| 剪辑 | 剪辑点（播放头处，或选区两端）、删除选区（后面自动接上）、仅保留选区、撤销 / 重做（每个文件各自 200 步） |
| 效果 | 音量 −40 ~ +20 dB（波形变红 = 削波）、淡入、淡出（输入数字，或直接拖波形左右上角的小方块） |
| 试听 | 实时按剪辑结果播放，有选区只放选区；拖音量 / 淡入淡出时立刻能听到 |
| 选区输入 | 开始 / 结束可直接输入 `90`、`1:30`、`00:01:30.5`；「播放头→开始 / 结束」边听边标 |
| 导出 | MP3 / WAV / FLAC / M4A / OGG / OPUS / WMA / 保持原格式；采样率、比特率、声道；单个或全部导出 |
| 保存位置 | 手机存储 `Music/K剪音`（系统不收的类型退到 `Download/K剪音`）；导出后可直接播放 / 分享 |
| 后台导出 | 前台服务 + 通知栏进度，可在通知里停止；切走、锁屏不会被杀 |

**和 PC 版的差别**：
- 没有「源文件目录 / 自定义输出目录」，固定存 `Music/K剪音`（手机上拿不到源文件的真实目录）。
- 「保持原格式」遇到视频文件（mp4/mkv/mov…）会按音轨编码换成音频扩展名（aac→m4a、mp3→mp3、opus→opus…），因为「音乐」目录只收音频。
- 键盘快捷键全部换成按钮。

## 关键实现

| 文件 | 作用 |
|---|---|
| `core/EditState.kt` | 非破坏性编辑模型，PC 版 `Edit.cs` 一比一移植 |
| `core/Ffmpeg.kt` | 调用内置 ffmpeg（参数用列表传，不经 shell）、解析进度、拼导出滤镜（同 PC 版 `BuildFilter`）、时间格式 |
| `core/Pcm.kt` | 读 WAV 波形峰值；`EditReader` 按剪辑结果实时混音；`PcmPlayer` 用 AudioTrack 试听 |
| `core/Formats.kt` | 格式表、「保持原格式」的扩展名规则、MediaStore MIME |
| `Studio.kt` | 全局状态和操作（对应 PC 版 MainForm 的逻辑）：导入、解码、编辑、试听、批量导出、存 MediaStore |
| `WaveModel.kt` + `ui/WaveCanvas.kt` | 波形状态 + 绘制和触摸手势（对应 PC 版 `WaveView.cs`） |
| `ExportService.kt` | 导出前台服务（dataSync），通知栏进度 / 停止 |
| `ui/App.kt` | 全部界面：文件条、波形卡、选区与效果、导出格式、导出结果、日志、底部导出栏 |

**ffmpeg 怎么带进 App**：`ffmpeg-android/build.sh` 在 CI 里用 NDK 交叉编译 ffmpeg 7.1.1（arm64，带 LAME / Vorbis / Opus，LGPL），
产物改名 `libffmpeg.so` 放进 `jniLibs/arm64-v8a`。`useLegacyPackaging = true` 让安装时解压到 `nativeLibraryDir`，
那里的文件系统允许执行（App 私有目录不行）。`build.sh` 不变时 CI 直接用缓存（ffmpeg 单独一个 job）。

**处理流程**：导入时把文件复制到 `cache/in/<id>/`（ffmpeg 只认真实路径）并探测时长和音轨 →
选中时解码成 44.1kHz/16bit 立体声临时 WAV（波形 + 试听都用它）→ 导出时 ffmpeg 直接读副本，写到 `cache/out/`，再拷进 MediaStore。
每次冷启动清空 `cache/in`、`cache/out`、试听临时文件（列表不跨启动保留，同 PC 版）。

## 构建

本地没装 Android SDK，push 到 `main` 后由 GitHub Actions 编译（第一次连 ffmpeg 约 15 分钟，之后 3~4 分钟）：

```bash
export HTTPS_PROXY=http://127.0.0.1:7897 HTTP_PROXY=http://127.0.0.1:7897
gh run list -R shawnbugs/k-audio -L 3
gh run watch <run_id> -R shawnbugs/k-audio
gh run download <run_id> -R shawnbugs/k-audio -n KAudio-<版本> -D /d/AiWork/Android/k-audio-app/apk
```

- 只出正式版（assembleRelease），固定签名 `app/debug.keystore`（本 App 独立生成），新包直接覆盖安装。
- 只支持 arm64 手机，Android 10 及以上。
