package zhiqiu.kongkim.media

enum class MediaTrack(val id: String, val label: String) {
    FIVE("5x", "5遍版"),
    NINE("9x", "9遍版");

    fun assetFile(audioOnly: Boolean): String =
        if (audioOnly) "${id}.mp3" else "${id}.mp4"

    companion object {
        fun fromId(id: String?): MediaTrack =
            entries.firstOrNull { it.id == id } ?: NINE
    }
}
