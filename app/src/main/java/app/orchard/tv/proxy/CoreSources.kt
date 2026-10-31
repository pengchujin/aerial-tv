package app.orchard.tv.proxy

/**
 * Where the optional proxy core is downloaded from, tried in this order. Every entry is the same
 * pinned file (CoreSpec): the order only decides which server is asked first, and the downloaded
 * bytes are accepted only if their SHA-256 matches the pin, whoever served them. Full https URLs
 * only; a download never mixes bytes from two entries (CoreInstaller).
 *
 * This file is the only place a build changes to prefer another mirror.
 */
internal object CoreSources {
    val URLS: List<String> = listOf(
        "https://github.com/MetaCubeX/mihomo/releases/download/v1.19.33/mihomo-linux-armv7-v1.19.33.gz",
    )
}
