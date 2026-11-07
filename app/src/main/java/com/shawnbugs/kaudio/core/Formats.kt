package com.shawnbugs.kaudio.core

/** video = 导出成视频（0.3.0 起，同 PC 版 v4） */
class FormatInfo(
    val name: String,
    val ext: String,
    val codec: String,
    val lossless: Boolean,
    val copy: Boolean,
    val video: Boolean = false,
)

object Formats {
    val list = listOf(
        FormatInfo("MP3", ".mp3", "libmp3lame", false, false),
        FormatInfo("WAV (PCM 16bit)", ".wav", "pcm_s16le", true, false),
        FormatInfo("FLAC (无损)", ".flac", "flac", true, false),
        FormatInfo("M4A (AAC)", ".m4a", "aac", false, false),
        FormatInfo("OGG (Vorbis)", ".ogg", "libvorbis", false, false),
        FormatInfo("OPUS", ".opus", "libopus", false, false),
        FormatInfo("WMA", ".wma", "wmav2", false, false),
        FormatInfo("保持原格式 (音频)", "", "copy", false, true),
        FormatInfo("视频 MP4 (H.264)", ".mp4", "libx264", false, false, true),
        FormatInfo("视频 MP4 (H.265)", ".mp4", "libx265", false, false, true),
        FormatInfo("视频 无损快剪", "", "copy", false, true, true),
    )

    /** 按名字找格式；0.2.0 以前存的「保持原格式」改名了 */
    fun indexOf(name: String?): Int =
        list.indexOfFirst { it.name == (if (name == "保持原格式") "保持原格式 (音频)" else name) }.coerceAtLeast(0)

    val rates = listOf("原始", "8000", "11025", "12000", "16000", "22050", "24000", "32000", "44100", "48000", "96000")
    val bitrates = listOf("默认", "32k", "48k", "64k", "96k", "128k", "160k", "192k", "256k", "320k")
    val channels = listOf("原始", "单声道", "立体声")

    // 视频参数（同 PC 版）
    val resolutions = listOf("原始", "1080p", "720p", "480p", "360p")
    val resHeights = intArrayOf(0, 1080, 720, 480, 360)
    val qualities = listOf("高画质", "标准", "小文件")
    val crfX264 = intArrayOf(18, 23, 28)
    val crfX265 = intArrayOf(22, 27, 31)
    val videoAudio = listOf("保留声音", "去掉声音")

    /** 无损快剪不得不重新编码时，这些容器能装 H.264，其余改存 MP4 */
    val videoContainers = setOf(".mp4", ".mkv", ".mov")

    /** 纯音频容器：「保持原格式」时沿用原扩展名 */
    private val audioExts = setOf(
        ".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".amr", ".aiff", ".aif", ".ac3", ".mka", ".m4b",
    )

    /** 剪辑/加效果后需要重新编码时，这些扩展名 ffmpeg 有默认编码器；其余改存 FLAC（同 PC 版） */
    private val reencodable = setOf(
        ".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".opus", ".wma", ".aiff", ".aif", ".ac3", ".mka",
    )

    /**
     * 「保持原格式」的输出扩展名。
     * PC 版直接用源扩展名；手机上视频文件（mp4/mkv…）不能存进「音乐」，所以视频按音轨编码选一个音频扩展名
     * （aac → m4a、mp3 → mp3…），等于「提取原音轨」。
     */
    fun keepExt(srcExt: String, audio: String?, reencode: Boolean): String {
        var ext = srcExt.lowercase()
        if (ext == ".m4b") ext = ".m4a"
        if (ext in audioExts) return if (reencode && ext !in reencodable) ".flac" else ext
        val codec = audio?.substringBefore(' ')?.substringBefore(',')?.lowercase() ?: ""
        val byCodec = when {
            codec == "aac" || codec == "alac" -> ".m4a"
            codec == "mp3" -> ".mp3"
            codec == "opus" -> ".opus"
            codec == "vorbis" -> ".ogg"
            codec == "flac" -> ".flac"
            codec == "ac3" -> ".ac3"
            codec.startsWith("pcm_") -> ".wav"
            codec.startsWith("wma") -> ".wma"
            codec.startsWith("amr") -> ".amr"
            else -> ".mka"   // Matroska 什么编码都能装
        }
        return if (reencode && byCodec !in reencodable) ".flac" else byCodec
    }

    // 存进 MediaStore 用的 MIME（必须是 audio 开头，系统才让放进「音乐」）。注意注释里别写「斜杠星号」，Kotlin 会当成嵌套注释
    fun mime(ext: String): String = when (ext.lowercase()) {
        ".mp3" -> "audio/mpeg"
        ".wav" -> "audio/x-wav"
        ".flac" -> "audio/flac"
        ".m4a" -> "audio/mp4"
        ".aac" -> "audio/aac"
        ".ogg", ".opus" -> "audio/ogg"
        ".wma" -> "audio/x-ms-wma"
        ".amr" -> "audio/amr"
        ".aiff", ".aif" -> "audio/x-aiff"
        ".ac3" -> "audio/ac3"
        ".mka" -> "audio/x-matroska"
        else -> "audio/*"
    }

    /** 视频存进「影片」用的 MIME（必须是 video 开头） */
    fun videoMime(ext: String): String = when (ext.lowercase()) {
        ".mkv" -> "video/x-matroska"
        ".mov" -> "video/quicktime"
        ".webm" -> "video/webm"
        ".avi" -> "video/x-msvideo"
        ".flv" -> "video/x-flv"
        ".wmv" -> "video/x-ms-wmv"
        ".3gp" -> "video/3gpp"
        ".ts" -> "video/mp2t"
        else -> "video/mp4"
    }

    /** 能分享进来/选择的文件 MIME */
    val pickMimes = arrayOf("audio/*", "video/*")
}
