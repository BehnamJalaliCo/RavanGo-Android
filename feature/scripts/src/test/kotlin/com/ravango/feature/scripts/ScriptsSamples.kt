package com.ravango.feature.scripts

import com.ravango.core.model.Script
import com.ravango.core.model.ScriptFolder
import com.ravango.feature.scripts.library.ScriptItem
import com.ravango.feature.scripts.library.ScriptsUiState
import com.ravango.feature.scripts.library.SearchText

/** Realistic sample data (Persian-first, with mixed English) for screenshot tests. */
internal object ScriptsSamples {
    private const val NOW = 1_790_000_000_000L
    private const val DAY = 86_400_000L

    val folders = listOf(
        ScriptFolder(id = "f1", name = "اینستاگرام", colorArgb = 0xFFF7718F, createdAt = NOW, updatedAt = NOW),
        ScriptFolder(id = "f2", name = "یوتیوب", colorArgb = 0xFF8B7CF6, createdAt = NOW, updatedAt = NOW),
        ScriptFolder(id = "f3", name = "Client work", colorArgb = 0xFF3CC4A4, createdAt = NOW, updatedAt = NOW),
    )

    val persianBody = """
        ## مقدمه
        سلام دوستان! امروز می‌خواهم دربارهٔ ==سه عادت ساده== صحبت کنم که بهره‌وری‌ام را دو برابر کرد. [مکث]
        اولین عادت، **برنامه‌ریزی شب قبل** است. [[لبخند بزن]]
        ## عادت دوم
        دومین عادت این است که صبح‌ها قبل از چک کردن گوشی، ده دقیقه پیاده‌روی کنم.
    """.trimIndent()

    val englishBody = """
        ## Hook
        Most people film their videos three times before they get one good take. Here's how I fixed that. [pause]
        **Step one**: write the script like you talk, not like you write.
    """.trimIndent()

    private fun script(id: String, title: String, body: String, folder: String?, fav: Boolean, daysAgo: Int) = Script(
        id = id, title = title, body = body, folderId = folder, isFavorite = fav,
        createdAt = NOW - daysAgo * DAY, updatedAt = NOW - daysAgo * DAY,
    )

    val items = listOf(
        ScriptItem(
            script("s1", "سه عادت ساده برای بهره‌وری بیشتر", persianBody, "f1", fav = true, daysAgo = 0),
            words = 186, durationMs = 82_000,
            preview = "سلام دوستان! امروز می‌خواهم دربارهٔ سه عادت ساده صحبت کنم که بهره‌وری‌ام را دو برابر کرد. اولین عادت، برنامه‌ریزی شب قبل است.",
            titleMatches = emptyList(), previewMatches = emptyList(),
        ),
        ScriptItem(
            script("s2", "How I film in one take", englishBody, "f3", fav = false, daysAgo = 1),
            words = 342, durationMs = 146_000,
            preview = "Most people film their videos three times before they get one good take. Here's how I fixed that. Step one: write the script like you talk.",
            titleMatches = emptyList(), previewMatches = emptyList(),
        ),
        ScriptItem(
            script("s3", "معرفی محصول جدید فروشگاه — کلکسیون پاییزه با تخفیف ویژهٔ اعضای باشگاه مشتریان", persianBody, "f2", fav = false, daysAgo = 3),
            words = 1240, durationMs = 531_000,
            preview = "کلکسیون پاییزهٔ ما بالاخره رسید! در این ویدیو همهٔ مدل‌ها را از نزدیک نشانتان می‌دهم.",
            titleMatches = emptyList(), previewMatches = emptyList(),
        ),
        ScriptItem(
            script("s4", "", "", null, fav = false, daysAgo = 12),
            words = 0, durationMs = 0, preview = "",
            titleMatches = emptyList(), previewMatches = emptyList(),
        ),
    )

    val loaded = ScriptsUiState(loading = false, items = items, folders = folders)
    val searching = loaded.copy(
        query = "عادت",
        items = listOf(
            items[0].let {
                val terms = SearchText.terms("عادت")
                it.copy(titleMatches = SearchText.matchRanges(it.script.title, terms), previewMatches = SearchText.matchRanges(it.preview, terms))
            },
        ),
    )
    val empty = ScriptsUiState(loading = false)
    val emptySearch = ScriptsUiState(loading = false, folders = folders, query = "دوربین")
    val loading = ScriptsUiState(loading = true, folders = folders)
}
