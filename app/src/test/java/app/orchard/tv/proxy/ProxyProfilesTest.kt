package app.orchard.tv.proxy

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ProxyProfilesTest {
    @get:Rule val temporary = TemporaryFolder()
    private lateinit var directory: File
    private lateinit var prefs: SharedPreferences

    @Before fun setUp() {
        directory = temporary.newFolder("proxy")
        prefs = RuntimeEnvironment.getApplication().getSharedPreferences("proxy-test", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    // Migration from the single-subscription layout

    @Test fun legacySubscriptionMigratesIntoLegacyProfile() {
        legacyInstall()
        val profiles = ProxyProfiles(directory, prefs)
        val profile = profiles.profiles.single()
        assertEquals("legacy-subscription", profile.id)
        assertEquals("subscription", profile.kind)
        assertEquals(URL, profile.url)
        assertEquals(1_700_000_000_000L, profile.updatedAt)
        assertEquals(24, profile.refreshHours)
        assertNull(profile.lastError)
        assertEquals("legacy-subscription", profiles.activeId)
        assertEquals(profile, profiles.active())
        assertEquals(YAML, profiles.file(profile.id).readText())
        assertTrue(File(directory, "profiles.json").isFile)
    }

    @Test fun legacyFileAndUrlAreKeptAsFallback() {
        legacyInstall()
        ProxyProfiles(directory, prefs)
        assertEquals(YAML, File(directory, "subscription.yaml").readText())
        assertEquals(URL, prefs.getString("subscriptionUrl", null))
    }

    @Test fun migratedIndexSurvivesReload() {
        legacyInstall()
        val first = ProxyProfiles(directory, prefs)
        val second = ProxyProfiles(directory, prefs)
        assertEquals(first.profiles, second.profiles)
        assertEquals("legacy-subscription", second.activeId)
        assertNull(second.profiles.single().lastError)
    }

    @Test fun migrationRunsOnlyOnce() {
        legacyInstall()
        ProxyProfiles(directory, prefs)
        val index = File(directory, "profiles.json").readText()
        // Later legacy changes (the mirror of the active profile) must not re-import over the profile.
        File(directory, "subscription.yaml").writeText("proxies: [changed]\n")
        prefs.edit().putString("subscriptionUrl", "https://other.example.invalid/sub").commit()
        val again = ProxyProfiles(directory, prefs)
        assertEquals(URL, again.profiles.single().url)
        assertEquals(YAML, again.file("legacy-subscription").readText())
        assertEquals(index, File(directory, "profiles.json").readText())
    }

    @Test fun noMigrationWithoutBothLegacyFileAndUrl() {
        File(directory, "subscription.yaml").writeText(YAML)
        val withoutUrl = ProxyProfiles(directory, prefs)
        assertTrue(withoutUrl.profiles.isEmpty())
        assertNull(withoutUrl.activeId)
        assertFalse(File(directory, "profiles.json").exists())
        assertEquals(YAML, File(directory, "subscription.yaml").readText())

        File(directory, "subscription.yaml").delete()
        prefs.edit().putString("subscriptionUrl", URL).commit()
        assertTrue(ProxyProfiles(directory, prefs).profiles.isEmpty())
        assertFalse(File(directory, "profiles.json").exists())
        assertEquals(URL, prefs.getString("subscriptionUrl", null))
    }

    @Test fun failedYamlCopyWritesNoIndexAndKeepsLegacy() {
        legacyInstall()
        block(File(directory, "profiles/legacy-subscription.yaml"))
        assertThrows(Exception::class.java) { ProxyProfiles(directory, prefs) }
        assertFalse(File(directory, "profiles.json").exists())
        assertFalse(File(directory, "profiles.json.bak").exists())
        assertEquals(YAML, File(directory, "subscription.yaml").readText())
        assertEquals(URL, prefs.getString("subscriptionUrl", null))

        unblock(File(directory, "profiles/legacy-subscription.yaml"))
        val retried = ProxyProfiles(directory, prefs)
        assertEquals("legacy-subscription", retried.activeId)
        assertEquals(YAML, retried.file("legacy-subscription").readText())
    }

    @Test fun failedIndexWriteKeepsLegacyAndRetriesOnNextStart() {
        legacyInstall()
        block(File(directory, "profiles.json"))
        assertThrows(Exception::class.java) { ProxyProfiles(directory, prefs) }
        // The YAML copy happened first, and the legacy source is untouched.
        assertEquals(YAML, File(directory, "profiles/legacy-subscription.yaml").readText())
        assertEquals(YAML, File(directory, "subscription.yaml").readText())
        assertEquals(URL, prefs.getString("subscriptionUrl", null))

        unblock(File(directory, "profiles.json"))
        val retried = ProxyProfiles(directory, prefs)
        assertEquals(URL, retried.profiles.single().url)
        assertEquals("legacy-subscription", retried.activeId)
        assertEquals(YAML, retried.file("legacy-subscription").readText())
        assertEquals(YAML, File(directory, "subscription.yaml").readText())
    }

    @Test fun corruptIndexFailsWithoutTouchingProfileYaml() {
        legacyInstall()
        File(directory, "profiles").mkdirs()
        File(directory, "profiles/legacy-subscription.yaml").writeText(YAML)
        File(directory, "profiles/other.yaml").writeText(OTHER_YAML)
        listOf("{\"profiles\": [", "", "not json", "{\"profiles\": [{\"id\": \"../x\", \"name\": \"x\", \"kind\": \"subscription\"}]}",
            "{\"profiles\": [{\"id\": \"a\", \"name\": \"x\", \"kind\": \"vmess\"}]}",
            "{\"profiles\": [{\"id\": \"a\", \"name\": \"a\", \"kind\": \"http\"}, {\"id\": \"a\", \"name\": \"b\", \"kind\": \"http\"}]}",
        ).forEach { text ->
            File(directory, "profiles.json").writeText(text)
            assertThrows(text, Exception::class.java) { ProxyProfiles(directory, prefs) }
            assertEquals(text, File(directory, "profiles.json").readText())
            assertEquals(YAML, File(directory, "profiles/legacy-subscription.yaml").readText())
            assertEquals(OTHER_YAML, File(directory, "profiles/other.yaml").readText())
            assertEquals(YAML, File(directory, "subscription.yaml").readText())
        }
    }

    @Test fun backupIndexIsRestoredInsteadOfRemigrating() {
        val profiles = ProxyProfiles(directory, prefs)
        val other = ProxyProfile("other", "Other", "subscription", url = "https://other.example.invalid/sub")
        val manual = ProxyProfile("manual", "Office", "http", host = "proxy.lan", port = 3128)
        profiles.file("other").writeText(OTHER_YAML)
        profiles.file("manual").writeText("proxies: [m]\n")
        profiles.commit(listOf(other, manual), "other")
        // A write interrupted after AtomicFile moved the index aside leaves only the backup.
        assertTrue(File(directory, "profiles.json").renameTo(File(directory, "profiles.json.bak")))
        legacyInstall()

        val restored = ProxyProfiles(directory, prefs)
        assertEquals(listOf(other, manual), restored.profiles)
        assertEquals("other", restored.activeId)
        assertFalse(File(directory, "profiles/legacy-subscription.yaml").exists())
        assertEquals(OTHER_YAML, restored.file("other").readText())
    }

    @Test fun missingIndexDoesNotDeleteExistingProfileYaml() {
        File(directory, "profiles").mkdirs()
        File(directory, "profiles/orphan.yaml").writeText(OTHER_YAML)
        assertTrue(ProxyProfiles(directory, prefs).profiles.isEmpty())
        assertEquals(OTHER_YAML, File(directory, "profiles/orphan.yaml").readText())

        legacyInstall()
        ProxyProfiles(directory, prefs)
        assertEquals(OTHER_YAML, File(directory, "profiles/orphan.yaml").readText())
        assertEquals(YAML, File(directory, "profiles/legacy-subscription.yaml").readText())
    }

    // Index limits and edits

    @Test fun sixtyFourProfilesAreAllowed() {
        val profiles = ProxyProfiles(directory, prefs)
        val many = (1..64).map { manualProfile("p$it") }
        profiles.commit(many, "p64")
        val reloaded = ProxyProfiles(directory, prefs)
        assertEquals(many, reloaded.profiles)
        assertEquals("p64", reloaded.activeId)
    }

    @Test fun sixtyFifthProfileIsRejectedWithoutChangingTheIndex() {
        val profiles = ProxyProfiles(directory, prefs)
        val many = (1..64).map { manualProfile("p$it") }
        profiles.commit(many, "p1")
        val index = File(directory, "profiles.json").readText()
        val error = assertThrows(IllegalArgumentException::class.java) { profiles.commit(many + manualProfile("p65"), "p1") }
        assertEquals("profile_limit_reached", error.message)
        assertEquals(many, profiles.profiles)
        assertEquals(index, File(directory, "profiles.json").readText())
    }

    @Test fun storedIndexOverLimitIsRejected() {
        val entries = JSONArray().also { array -> (1..65).forEach { array.put(manualProfile("p$it").privateJson()) } }
        File(directory, "profiles.json").writeText(JSONObject().put("version", 1).put("profiles", entries).toString())
        val error = assertThrows(IllegalArgumentException::class.java) { ProxyProfiles(directory, prefs) }
        assertEquals("profile_limit_reached", error.message)
    }

    @Test fun deletingActiveProfileKeepsOthersAndLeavesNoneActive() {
        val profiles = ProxyProfiles(directory, prefs)
        val a = ProxyProfile("a", "A", "subscription", url = URL, updatedAt = 5L, lastAttemptAt = 6L)
        val b = ProxyProfile("b", "B", "subscription", url = "https://b.example.invalid/sub")
        val c = ProxyProfile("c", "C", "socks5", host = "socks.lan", port = 1080, username = "u", password = "p", tls = true)
        listOf(a, b, c).forEach { profiles.file(it.id).writeText("proxies: [${it.id}]\n") }
        profiles.commit(listOf(a, b, c), "b")

        // The storage half of ProxyManager.deleteProfile for the active id.
        profiles.commit(profiles.profiles.filterNot { it.id == "b" }, profiles.activeId?.takeUnless { it == "b" })
        profiles.file("b").delete()

        val reloaded = ProxyProfiles(directory, prefs)
        assertEquals(listOf(a, c), reloaded.profiles)
        assertNull(reloaded.activeId)
        assertNull(reloaded.active())
        assertEquals("proxies: [a]\n", reloaded.file("a").readText())
        assertEquals("proxies: [c]\n", reloaded.file("c").readText())
        assertFalse(reloaded.file("b").exists())
    }

    @Test fun activeIdOfMissingProfileLoadsAsNone() {
        File(directory, "profiles.json").writeText(JSONObject().put("activeProfileId", "gone")
            .put("profiles", JSONArray().put(manualProfile("kept").privateJson())).toString())
        val profiles = ProxyProfiles(directory, prefs)
        assertNull(profiles.activeId)
        assertEquals("kept", profiles.profiles.single().id)
    }

    @Test fun nullFieldsAreNotReadBackAsTheTextNull() {
        val profiles = ProxyProfiles(directory, prefs)
        // A profile could legally be named "null"; a JSON null active id must still mean none.
        profiles.commit(listOf(manualProfile("null"), manualProfile("ok")), null)
        val reloaded = ProxyProfiles(directory, prefs)
        assertNull(reloaded.activeId)
        assertNull(reloaded.profiles.first { it.id == "ok" }.lastError)
    }

    @Test fun lastErrorRoundTripsOnlyAsFixedCode() {
        val profiles = ProxyProfiles(directory, prefs)
        profiles.commit(listOf(manualProfile("a").copy(lastError = "subscription_dns_failed"), manualProfile("b")), "a")
        val reloaded = ProxyProfiles(directory, prefs)
        assertEquals("subscription_dns_failed", reloaded.find("a").lastError)
        assertNull(reloaded.find("b").lastError)

        val tampered = JSONObject(File(directory, "profiles.json").readText())
        tampered.getJSONArray("profiles").getJSONObject(0).put("lastError", "failed for $URL")
        File(directory, "profiles.json").writeText(tampered.toString())
        assertNull(ProxyProfiles(directory, prefs).find("a").lastError)
    }

    @Test fun profileFileRejectsPathTraversal() {
        val profiles = ProxyProfiles(directory, prefs)
        listOf("../profiles", "a/b", "a\\b", "", ".", "x".repeat(81), "a.yaml").forEach { id ->
            val error = assertThrows(id, IllegalArgumentException::class.java) { profiles.file(id) }
            assertEquals("invalid_profile_id", error.message)
        }
        assertEquals(File(directory, "profiles/legacy-subscription.yaml"), profiles.file("legacy-subscription"))
    }

    @Test fun unknownProfileLookupFailsWithFixedCode() {
        assertEquals("unknown_profile", assertThrows(IllegalStateException::class.java) {
            ProxyProfiles(directory, prefs).find("missing")
        }.message)
    }

    @Test fun publicStatusNeverContainsSecrets() {
        val subscription = ProxyProfile("s", "Sub", "subscription", url = URL)
        val manual = ProxyProfile("m", "Office", "http", host = "secret-host.example.invalid", port = 3128,
            username = "alice-user", password = "hunter2-pass", tls = true)
        val status = subscription.status("s").toString() + manual.status("s").toString()
        listOf(TOKEN, "sub.example.invalid", "secret-host", "alice-user", "hunter2-pass").forEach {
            assertFalse(it, status.contains(it))
        }
        assertEquals(setOf("id", "name", "kind", "active", "updatedAt", "refreshHours", "lastError"),
            manual.status(null).keys().asSequence().toSet())
    }

    private fun legacyInstall() {
        File(directory, "subscription.yaml").writeText(YAML)
        prefs.edit().putString("subscriptionUrl", URL).putLong("subscriptionUpdatedAt", 1_700_000_000_000L).commit()
    }

    private fun manualProfile(id: String) = ProxyProfile(id, "Profile $id", "http", host = "proxy.lan", port = 8080, refreshHours = 0)

    /** Makes an AtomicFile target unwritable on every OS: a non-empty directory at both the file and its backup path. */
    private fun block(file: File) {
        listOf(file, File(file.path + ".bak")).forEach { File(it, "keep").apply { parentFile!!.mkdirs(); writeText("x") } }
    }

    private fun unblock(file: File) {
        listOf(file, File(file.path + ".bak")).forEach { it.deleteRecursively() }
    }

    private companion object {
        const val TOKEN = "Tk9QRS1zZWNyZXQtdG9rZW4"
        const val URL = "https://sub.example.invalid/api/v1/client/subscribe?token=$TOKEN"
        const val OTHER_YAML = "proxies:\n  - {name: other, type: ss, server: o.example.invalid, port: 1, cipher: aes-128-gcm, password: p}\n"
        val YAML = """
            |mixed-port: 7890
            |proxies:
            |  - {name: "香港 01", type: ss, server: hk.example.invalid, port: 443, cipher: aes-128-gcm, password: pw}
            |proxy-groups:
            |  - {name: 节点选择, type: select, proxies: ["香港 01"]}
            |rules:
            |  - MATCH,节点选择
            |""".trimMargin()
    }
}
