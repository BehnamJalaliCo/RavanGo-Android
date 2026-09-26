package com.ravango.core.common.format

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.util.Locale

class FormattersTest {
    private val fa = Locale.forLanguageTag("fa")

    @Test fun `bytes use Persian digits and decimal separator`() {
        assertThat(formatBytes(1_572_864, fa)).isEqualTo("۱٫۵ مگابایت")
        assertThat(formatBytes(1_572_864, Locale.US)).isEqualTo("1.5 MB")
    }

    @Test fun `tenths use Persian decimal separator`() {
        assertThat(formatDuration(65_300_000, fa, showTenths = true)).isEqualTo("۱:۰۵٫۳")
        assertThat(formatDuration(65_300_000, Locale.US, showTenths = true)).isEqualTo("1:05.3")
    }

    @Test fun `normalize accepts Persian decimal separator`() {
        assertThat("۱٫۵".normalizeDigits()).isEqualTo("1.5")
    }
}
