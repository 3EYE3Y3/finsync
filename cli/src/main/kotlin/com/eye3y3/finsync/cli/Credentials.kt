package com.eye3y3.finsync.cli

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/** What the CLI remembers between runs. It holds a token, so it lives outside the repository with mode 600. */
data class Credentials(val server: String, val deviceId: String, val userId: String, val token: String) {
    companion object {
        val defaultPath: Path get() = Path.of(System.getProperty("user.home"), ".config", "finsync", "credentials.json")

        fun load(path: Path = defaultPath): Credentials? {
            if (!Files.exists(path)) return null
            val o = Json.parseToJsonElement(Files.readString(path)) as? JsonObject ?: return null
            fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull
            return Credentials(s("server") ?: return null, s("deviceId") ?: return null, s("userId") ?: "", s("token") ?: return null)
        }
    }

    /** Writes the file readable by its owner only, from the moment it exists. */
    fun save(path: Path = defaultPath) {
        Files.createDirectories(path.parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val tmp = Files.createTempFile(path.parent, "credentials", ".tmp", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
        val json = buildJsonObject { put("server", server); put("deviceId", deviceId); put("userId", userId); put("token", token) }
        Files.writeString(tmp, json.toString())
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
