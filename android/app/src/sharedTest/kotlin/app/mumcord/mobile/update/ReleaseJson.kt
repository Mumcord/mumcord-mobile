// SPDX-License-Identifier: AGPL-3.0-only

package app.mumcord.mobile.update

/**
 * An answer of GitHub's latest-release API as the release workflow's releases look, for the
 * tests; every field can be changed to check that a wrong one is refused.
 */
fun releaseJson(
    tag: String = "v0.3.0",
    draft: String = "false",
    prerelease: String = "false",
    page: String = "https://github.com/Mumcord/mumcord-mobile/releases/tag/$tag",
    apkName: String = "mumcord-mobile-0.3.0-arm64.apk",
    apkUrl: String = "https://github.com/Mumcord/mumcord-mobile/releases/download/v0.3.0/mumcord-mobile-0.3.0-arm64.apk",
    assets: String = """[
        {"name": "SHA256SUMS", "browser_download_url": "https://github.com/Mumcord/mumcord-mobile/releases/download/v0.3.0/SHA256SUMS"},
        {"name": "$apkName", "browser_download_url": "$apkUrl", "size": 41000000}
    ]""",
): String = """
    {
        "tag_name": "$tag",
        "draft": $draft,
        "prerelease": $prerelease,
        "html_url": "$page",
        "assets": $assets
    }
""".trimIndent()
