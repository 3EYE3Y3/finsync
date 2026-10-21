package com.eye3y3.finsync.sync

import java.io.File

actual fun freeBytes(path: String): Long? = File(path).takeIf { it.exists() }?.usableSpace
