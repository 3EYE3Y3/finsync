package com.eye3y3.finsync.app

/** "1 track", "2 tracks". */
internal fun count(n: Int, one: String, many: String = one + "s") = "$n ${pick(n, one, many)}"

internal fun pick(n: Int, one: String, many: String) = if (n == 1) one else many
