package app.aria

/** Платформенные функции: реализации лежат в androidMain и desktopMain. */
expect fun dataDir(): String
expect fun readTextFile(path: String): String?
expect fun writeTextFile(path: String, text: String)
expect fun nowMillis(): Long
