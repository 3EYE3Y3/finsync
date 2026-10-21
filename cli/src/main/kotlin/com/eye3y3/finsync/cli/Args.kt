package com.eye3y3.finsync.cli

/** Parsed `--name value` / `--name=value` options. [flags] are options that take no value. */
class Args(val command: String?, private val values: Map<String, List<String>>, private val flags: Set<String>) {
    fun get(name: String): String? = values[name]?.lastOrNull()
    fun all(name: String): List<String> = values[name].orEmpty()
    fun flag(name: String) = name in flags
    fun require(name: String): String = get(name) ?: throw IllegalArgumentException("Missing --$name. Run finsync without arguments for help.")

    companion object {
        private val FLAG_NAMES = setOf("allow-large-delete", "list")

        fun parse(argv: List<String>): Args {
            val values = HashMap<String, MutableList<String>>()
            val flags = HashSet<String>()
            var i = 0
            val command = argv.firstOrNull()?.takeIf { !it.startsWith("--") }?.also { i = 1 }
            while (i < argv.size) {
                val raw = argv[i++]
                if (!raw.startsWith("--")) throw IllegalArgumentException("Unexpected \"$raw\". Options start with --.")
                val name = raw.removePrefix("--").substringBefore('=')
                when {
                    name in FLAG_NAMES -> flags += name
                    '=' in raw -> values.getOrPut(name) { ArrayList() } += raw.substringAfter('=')
                    i < argv.size -> values.getOrPut(name) { ArrayList() } += argv[i++]
                    else -> throw IllegalArgumentException("--$name needs a value.")
                }
            }
            return Args(command, values, flags)
        }
    }
}

/** Parses sizes like 5G, 500M, 1.5G, 64K or plain bytes (binary multiples: 1G = 1024 x 1024 x 1024). */
fun parseSize(text: String): Long {
    val m = Regex("""(\d+(?:\.\d+)?)\s*([KMGT]?)(?:i?B)?""", RegexOption.IGNORE_CASE).matchEntire(text.trim())
        ?: throw IllegalArgumentException("\"$text\" is not a size. Use something like 500M or 5G.")
    val unit = when (m.groupValues[2].uppercase()) { "K" -> 1L shl 10; "M" -> 1L shl 20; "G" -> 1L shl 30; "T" -> 1L shl 40; else -> 1L }
    return (m.groupValues[1].toDouble() * unit).toLong()
}

/** 1.5 GB style text for people. */
fun humanBytes(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1f GB".format(n / 1e9)
    n >= 1_000_000 -> "%.1f MB".format(n / 1e6)
    n >= 1_000 -> "%.1f kB".format(n / 1e3)
    else -> "$n B"
}
