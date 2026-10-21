package com.eye3y3.finsync.app

import com.eye3y3.finsync.BuildConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GitHubUpdatesTest {
    private val api = "https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest"
    private val dl = "https://github.com/${BuildConfig.GITHUB_REPO}/releases/download/v0.2.0"
    private val latest = """{"tag_name":"v0.2.0","html_url":"https://github.com/x/releases/tag/v0.2.0","assets":[
        {"name":"finsync-0.2.0.apk","browser_download_url":"$dl/finsync-0.2.0.apk"},
        {"name":"version.json","browser_download_url":"$dl/version.json"}]}"""

    @Test fun readsTheVersionAndTheApkLink() {
        val pages = mapOf(api to latest, "$dl/version.json" to """{"versionCode":9,"versionName":"0.2.0","apk":"finsync-0.2.0.apk"}""")
        assertEquals(GitHubUpdates.Release(9, "0.2.0", "$dl/finsync-0.2.0.apk"), GitHubUpdates.latest { pages.getValue(it) })
    }

    @Test fun aReleaseWithoutVersionJsonIsIgnored() {
        assertNull(GitHubUpdates.latest { """{"assets":[{"name":"finsync.apk","browser_download_url":"x"}]}""" })
    }
}
