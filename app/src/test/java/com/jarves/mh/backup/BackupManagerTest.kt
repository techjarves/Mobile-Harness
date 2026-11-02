package com.jarves.mh.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream

class BackupManagerTest {
    @get:Rule val temp = TemporaryFolder()

    private class FakeStores(var prefs: JSONObject = JSONObject(), var secrets: JSONObject = JSONObject()) : BackupStores {
        override fun exportPrefs() = prefs
        override fun importPrefs(prefs: JSONObject) { this.prefs = prefs }
        override fun exportSecrets() = secrets
        override fun importSecrets(secrets: JSONObject) { this.secrets = secrets }
    }

    private fun projectsPrefs(vararg ids: String) = JSONObject().put(
        "projects_json",
        JSONObject().put("t", "string").put("v", JSONArray().apply { ids.forEach { put(JSONObject().put("id", it)) } }.toString()),
    )

    private fun projectList(stores: FakeStores): List<JSONObject> {
        val array = JSONArray(stores.prefs.getJSONObject("projects_json").getString("v"))
        return (0 until array.length()).map(array::getJSONObject)
    }

    private fun projectIds(stores: FakeStores) = projectList(stores).map { it.getString("id") }

    private fun File.write(path: String, text: String) = File(this, path).apply { parentFile.mkdirs(); writeText(text) }

    private fun sourceApp(): Pair<File, FakeStores> {
        val dir = temp.newFolder("source")
        dir.write("workspaces/p1/src/main.kt", "fun main() {}")
        dir.write("workspaces/p1/.git/HEAD", "ref: refs/heads/main")
        dir.write("workspaces/p1/package.json", "{}")
        dir.write("workspaces/p1/node_modules/lib/index.js", "junk")
        dir.write("workspaces/p1/dist/out.js", "junk")
        dir.write("workspaces/p1/gradlew", "#!/bin/sh").setExecutable(true)
        dir.write("workspaces/p2/build/notes.txt", "hand-written build folder")
        dir.write("workspaces/github-auth/scratch.log", "scratch")
        dir.write("chats/p1/index.json", "[]")
        dir.write("runtime/ubuntu/root/.gitconfig", "[user]\n name = me")
        dir.write("runtime/ubuntu/root/.config/gh/hosts.yml", "token: x")
        dir.write("runtime/ubuntu/root/.claude/cache/big.bin", "cache")
        dir.write("runtime/ubuntu/root/.bash_history", "not backed up")
        val stores = FakeStores(
            prefs = projectsPrefs("p1", "p2"),
            secrets = JSONObject().put("ANTHROPIC", JSONArray().put(JSONObject().put("id", "k").put("secret", "sk-1"))),
        )
        return dir to stores
    }

    private fun backup(source: File, stores: FakeStores, password: String?): ByteArray {
        val output = ByteArrayOutputStream()
        BackupManager(source, stores, "1.0.9", 10).createBackup(output, password?.toCharArray())
        return output.toByteArray()
    }

    @Test
    fun `encrypted backup restores files, settings, keys and logins into a fresh install`() {
        val (source, stores) = sourceApp()
        val bytes = backup(source, stores, "hunter22")

        val target = temp.newFolder("target")
        target.write("workspaces/old/file.txt", "old project")
        target.write("runtime/ubuntu/root/.profile", "fresh")
        val targetStores = FakeStores(prefs = projectsPrefs("old"))
        val progress = mutableListOf<Int?>()
        val summary = BackupManager(target, targetStores, "1.0.9", 10)
            .restore(ByteArrayInputStream(bytes), "hunter22".toCharArray(), includeSettings = true) { progress += it.doneBytes.toInt() }

        assertEquals(2, summary.projects)
        assertTrue(summary.includesSecrets)
        assertEquals("fun main() {}", File(target, "workspaces/p1/src/main.kt").readText())
        assertTrue(File(target, "workspaces/p1/.git/HEAD").isFile)
        assertTrue(File(target, "workspaces/p1/gradlew").canExecute())
        assertTrue(File(target, "workspaces/p2/build/notes.txt").isFile)
        assertFalse(File(target, "workspaces/p1/node_modules").exists())
        assertFalse(File(target, "workspaces/p1/dist").exists())
        assertFalse(File(target, "workspaces/github-auth").exists())
        assertEquals("old project", File(target, "workspaces/old/file.txt").readText())
        assertTrue(File(target, "chats/p1/index.json").isFile)
        assertEquals("[user]\n name = me", File(target, "runtime/ubuntu/root/.gitconfig").readText())
        assertTrue(File(target, "runtime/ubuntu/root/.config/gh/hosts.yml").isFile)
        assertFalse(File(target, "runtime/ubuntu/root/.claude/cache").exists())
        assertFalse(File(target, "runtime/ubuntu/root/.bash_history").exists())
        assertEquals("sk-1", targetStores.secrets.getJSONArray("ANTHROPIC").getJSONObject(0).getString("secret"))
        assertEquals(listOf("old", "p1", "p2"), projectIds(targetStores))
        assertFalse(File(target, "restore-staging").exists())
        assertTrue(progress.isNotEmpty())
    }

    @Test
    fun `backup without password leaves out keys and logins and is a readable zip`() {
        val (source, stores) = sourceApp()
        val bytes = backup(source, stores, null)
        val stream = ByteArrayInputStream(bytes)
        assertFalse(BackupCrypto.readHeader(stream).encrypted)
        val names = mutableListOf<String>()
        ZipInputStream(stream).use { zip -> while (true) names += (zip.nextEntry ?: break).name }
        assertFalse("secrets.json" in names)
        assertTrue(names.none { it.startsWith("home/") })
        assertTrue("files/workspaces/p1/src/main.kt" in names)

        val targetStores = FakeStores(secrets = JSONObject().put("KEEP", JSONArray()))
        BackupManager(temp.newFolder("target"), targetStores, "1.0.9", 10).restore(ByteArrayInputStream(bytes), null)
        assertTrue(targetStores.secrets.has("KEEP"))
    }

    @Test
    fun `restoring onto the same phone adds copies and keeps everything already there`() {
        val (source, stores) = sourceApp()
        stores.prefs.getJSONObject("projects_json").put(
            "v",
            JSONArray().put(JSONObject().put("id", "p1").put("name", "Shop").put("slug", "shop")).put(JSONObject().put("id", "p2").put("name", "Blog")).toString(),
        )
        val bytes = backup(source, stores, "hunter22")
        // The phone kept working after the backup: p1 changed, a new project appeared.
        source.write("workspaces/p1/src/main.kt", "changed on phone")
        source.write("workspaces/p3/new.txt", "new")
        val phoneStores = FakeStores(
            prefs = projectsPrefs("p1", "p2", "p3").put("theme_mode", JSONObject().put("t", "string").put("v", "light")),
            secrets = JSONObject().put("PHONE", JSONArray()),
        )

        val summary = BackupManager(source, phoneStores, "1.0.9", 10, restoredNameSuffix = " (restored)")
            .restore(ByteArrayInputStream(bytes), "hunter22".toCharArray())

        assertEquals(2, summary.projects)
        assertFalse(summary.includesSecrets)
        val projects = projectList(phoneStores)
        assertEquals(5, projects.size)
        assertEquals(listOf("p1", "p2", "p3"), projects.take(3).map { it.getString("id") })
        val copies = projects.drop(3)
        assertEquals(listOf("Shop (restored)", "Blog (restored)"), copies.map { it.getString("name") })
        assertFalse(copies.first().has("slug"))
        assertEquals("changed on phone", File(source, "workspaces/p1/src/main.kt").readText())
        assertTrue(File(source, "workspaces/p3/new.txt").isFile)
        val copyId = copies.first().getString("id")
        assertEquals("fun main() {}", File(source, "workspaces/$copyId/src/main.kt").readText())
        assertTrue(File(source, "chats/$copyId/index.json").isFile)
        // Settings and keys were not part of this restore.
        assertEquals("light", phoneStores.prefs.getJSONObject("theme_mode").getString("v"))
        assertTrue(phoneStores.secrets.has("PHONE"))
        assertFalse(phoneStores.secrets.has("ANTHROPIC"))
    }

    @Test
    fun `wrong password fails before touching existing data`() {
        val (source, stores) = sourceApp()
        val bytes = backup(source, stores, "hunter22")
        try {
            BackupCrypto.verifyPassword(ByteArrayInputStream(bytes), "nope".toCharArray())
            fail("expected wrong password")
        } catch (_: WrongBackupPasswordException) {
        }
        val target = temp.newFolder("target")
        target.write("workspaces/old/file.txt", "keep me")
        try {
            BackupManager(target, FakeStores(), "1.0.9", 10).restore(ByteArrayInputStream(bytes), "nope".toCharArray())
            fail("expected wrong password")
        } catch (_: WrongBackupPasswordException) {
        }
        assertEquals("keep me", File(target, "workspaces/old/file.txt").readText())
    }

    @Test
    fun `truncated backup is rejected and existing data survives`() {
        val (source, stores) = sourceApp()
        for (password in listOf("hunter22", null)) {
            val bytes = backup(source, stores, password)
            val target = temp.newFolder()
            target.write("workspaces/old/file.txt", "keep me")
            try {
                BackupManager(target, FakeStores(), "1.0.9", 10)
                    .restore(ByteArrayInputStream(bytes.copyOf(bytes.size / 2)), password?.toCharArray())
                fail("expected failure for truncated backup")
            } catch (_: java.io.IOException) {
            }
            assertEquals("keep me", File(target, "workspaces/old/file.txt").readText())
        }
    }

    @Test
    fun `encrypted stream round trips data larger than one segment`() {
        val data = ByteArray(BackupCrypto.SEGMENT_BYTES * 2 + 123) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()
        BackupCrypto.openWriter(output, "pw".toCharArray(), iterations = 1_000).use { it.write(data) }
        val restored = BackupCrypto.openReader(ByteArrayInputStream(output.toByteArray()), "pw".toCharArray()).readBytes()
        assertTrue(data.contentEquals(restored))
    }

    @Test
    fun `not a backup file is rejected`() {
        try {
            BackupCrypto.readHeader(ByteArrayInputStream("PK\u0003\u0004 zip".toByteArray()))
            fail("expected invalid backup")
        } catch (_: InvalidBackupException) {
        }
    }
}
