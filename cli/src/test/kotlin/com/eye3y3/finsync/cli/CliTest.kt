package com.eye3y3.finsync.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CliTest {
    @Test fun httpRequiresExplicitOptInBeforeConnecting() {
        val dir = Files.createTempDirectory("finsync-cli-test")
        try {
            val creds = dir.resolve("c.json")
            for (server in listOf("http://192.168.1.10:8096", "HTTP://nas.local:8096", "http://music.example.com")) {
                val (code, text) = run("login", "--server", server, creds = creds)
                assertEquals(EXIT_ERROR, code)
                assertTrue("does not encrypt" in text, text)
                assertTrue("--allow-http" in text, text)
            }
            Credentials("http://nas.local:8096", "d", "u", "t").save(creds)
            val (code, text) = run("status", creds = creds)
            assertEquals(EXIT_ERROR, code)
            assertTrue("--allow-http" in text, text)
            assertTrue(Args.parse(listOf("login", "--server", "http://nas.local", "--allow-http")).flag("allow-http"))
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class) dir.deleteRecursively()
        }
    }

    @Test fun explicitHttpOptInKeepsWarningAndAllowsConnection() {
        val buf = ByteArrayOutputStream()
        val cli = Cli(PrintStream(buf))
        cli.checkTransport("http://nas.local:8096", Args.parse(listOf("login", "--allow-http")))
        assertTrue("does not encrypt" in buf.toString())
        buf.reset()
        cli.checkTransport("https://nas.local:8096", Args.parse(listOf("login")))
        assertEquals("", buf.toString())
    }

    @Test fun sizesParse() {
        assertEquals(5L shl 30, parseSize("5G"))
        assertEquals(500L shl 20, parseSize("500M"))
        assertEquals((1.5 * (1L shl 30)).toLong(), parseSize("1.5g"))
        assertEquals(64L shl 10, parseSize("64K"))
        assertEquals(1234, parseSize("1234"))
        assertEquals(5L shl 30, parseSize("5 GiB"))
        assertFailsWith<IllegalArgumentException> { parseSize("lots") }
        assertFailsWith<IllegalArgumentException> { parseSize("-5G") }
    }

    @Test fun argsParse() {
        val a = Args.parse(listOf("sync", "--dest", "/x", "--artist", "A", "--artist=B", "--allow-large-delete", "--max-bytes", "5G"))
        assertEquals("sync", a.command)
        assertEquals("/x", a.get("dest"))
        assertEquals(listOf("A", "B"), a.all("artist"))
        assertTrue(a.flag("allow-large-delete"))
        assertTrue(!a.flag("list"))
    }

    @Test fun badArgsGivePlainErrors() {
        assertFailsWith<IllegalArgumentException> { Args.parse(listOf("sync", "--dest")) }
        assertFailsWith<IllegalArgumentException> { Args.parse(listOf("sync", "stray")) }
        assertFailsWith<IllegalArgumentException> { Args.parse(listOf("sync")).require("dest") }
    }

    @Test fun credentialsFileIsOwnerOnlyAndRoundTrips() {
        val dir = Files.createTempDirectory("finsync-cli-test")
        try {
            val file = dir.resolve("conf/credentials.json")
            Credentials("http://s:8096", "dev", "u1", "tok").save(file)
            assertEquals("rw-------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
            assertEquals("rwx------", PosixFilePermissions.toString(Files.getPosixFilePermissions(file.parent)))
            assertEquals(Credentials("http://s:8096", "dev", "u1", "tok"), Credentials.load(file))
            assertEquals(null, Credentials.load(dir.resolve("none.json")))
            assertEquals(listOf("credentials.json"), Files.list(file.parent).use { s -> s.map { it.fileName.toString() }.toList() })
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class) dir.deleteRecursively()
        }
    }

    private fun run(vararg argv: String, creds: java.nio.file.Path): Pair<Int, String> {
        val buf = ByteArrayOutputStream()
        val code = Cli(PrintStream(buf), creds).run(argv.toList())
        return code to buf.toString()
    }

    @Test fun commandsNeedingAccountExitWith3WhenSignedOut() {
        val dir = Files.createTempDirectory("finsync-cli-test")
        try {
            val creds = dir.resolve("none.json")
            val (code, text) = run("status", creds = creds)
            assertEquals(EXIT_SIGNED_OUT, code)
            assertTrue("not signed in" in text)
            assertEquals(EXIT_SIGNED_OUT, run("plan", "--dest", dir.resolve("d").toString(), creds = creds).first)
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class) dir.deleteRecursively()
        }
    }

    @Test fun destIsRequiredWithNoDefault() {
        val dir = Files.createTempDirectory("finsync-cli-test")
        try {
            Credentials("http://127.0.0.1:1", "d", "u", "t").save(dir.resolve("c.json"))
            for (cmd in listOf("plan", "sync")) {
                val (code, text) = run(cmd, creds = dir.resolve("c.json"))
                assertEquals(EXIT_ERROR, code)
                assertTrue("Missing --dest" in text, text)
            }
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class) dir.deleteRecursively()
        }
    }

    @Test fun helpAndUnknownCommand() {
        val dir = Files.createTempDirectory("finsync-cli-test")
        try {
            val creds = dir.resolve("c.json")
            assertEquals(EXIT_OK, run(creds = creds).first)
            val (code, text) = run("bogus", creds = creds)
            assertEquals(EXIT_ERROR, code)
            assertNotNull(text.lines().firstOrNull { "finsync login" in it })
        } finally {
            @OptIn(kotlin.io.path.ExperimentalPathApi::class) dir.deleteRecursively()
        }
    }
}
