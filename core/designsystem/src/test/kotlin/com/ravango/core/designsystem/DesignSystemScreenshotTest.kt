package com.ravango.core.designsystem

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudUpload
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FlipCameraAndroid
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Subtitles
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.ravango.core.designsystem.component.ColorSwatchRow
import com.ravango.core.designsystem.component.EmptyState
import com.ravango.core.designsystem.component.GlassSurface
import com.ravango.core.designsystem.component.GradientBackground
import com.ravango.core.designsystem.component.ProBadge
import com.ravango.core.designsystem.component.RgButtonSize
import com.ravango.core.designsystem.component.RgCard
import com.ravango.core.designsystem.component.RgChip
import com.ravango.core.designsystem.component.RgDivider
import com.ravango.core.designsystem.component.RgGroup
import com.ravango.core.designsystem.component.RgIconButton
import com.ravango.core.designsystem.component.RgLabeledSlider
import com.ravango.core.designsystem.component.RgListItem
import com.ravango.core.designsystem.component.RgOutlineButton
import com.ravango.core.designsystem.component.RgPrimaryButton
import com.ravango.core.designsystem.component.RgProgressBar
import com.ravango.core.designsystem.component.RgSecondaryButton
import com.ravango.core.designsystem.component.RgSegmentedControl
import com.ravango.core.designsystem.component.RgSwitch
import com.ravango.core.designsystem.component.RgTag
import com.ravango.core.designsystem.component.RgTextButton
import com.ravango.core.designsystem.component.RgTextField
import com.ravango.core.designsystem.component.RgTopBar
import com.ravango.core.designsystem.component.SectionHeader
import com.ravango.core.designsystem.component.ShimmerBox
import com.ravango.core.designsystem.theme.Palette
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.designsystem.theme.Spacing
import com.ravango.core.testing.captureAllVariants
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DesignSystemScreenshotTest {

    @Test
    fun gallery() = captureAllVariants("designsystem-gallery") { Gallery() }

    @Test
    fun buttons() = captureAllVariants("designsystem-buttons") { Sheet { Buttons() } }

    @Test
    fun controls() = captureAllVariants("designsystem-controls") { Sheet { Controls() } }

    @Test
    fun lists() = captureAllVariants("designsystem-lists") { Sheet { Lists() } }

    @Test
    fun states() = captureAllVariants("designsystem-states") { Sheet { States() } }
}

@Composable
private fun Sheet(content: @Composable ColumnScope.() -> Unit) {
    GradientBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(vertical = Spacing.md), verticalArrangement = Arrangement.spacedBy(Spacing.md), content = content)
    }
}

@Composable
private fun Label(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = RgTheme.colors.textTertiary, modifier = Modifier.padding(horizontal = Spacing.gutter))
}

@Composable
private fun Gallery() {
    GradientBackground {
        Column(Modifier.fillMaxSize().statusBarsPadding(), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
            RgTopBar("روان‌گو RavanGo", subtitle = "Design system", actions = { RgIconButton(Icons.Rounded.Settings, null, {}) })
            Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                RgPrimaryButton("ضبط ویدیو / Record", {}, icon = Icons.Rounded.Videocam, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                    RgSecondaryButton("ثانویه", {}, size = RgButtonSize.SMALL)
                    RgChip("انتخاب", selected = true, onClick = {})
                    RgChip("چیپ", selected = false, onClick = {})
                    ProBadge()
                }
                RgSegmentedControl(listOf("9:16", "16:9", "1:1"), "9:16", {}, { it })
                RgCard(Modifier.fillMaxWidth()) {
                    Text("کارت / Card", style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
                    RgLabeledSlider("صاف کردن پوست", 45f, {})
                }
                GlassSurface(Modifier.fillMaxWidth()) { Text("Glass surface — شیشه‌ای", color = RgTheme.colors.textPrimary) }
            }
            RgGroup(title = "تنظیمات") {
                RgListItem("زیرنویس خودکار", subtitle = "Auto captions", icon = Icons.Rounded.Subtitles, trailing = { RgSwitch(true, {}) })
                RgDivider()
                RgListItem("حالت تیره", subtitle = "Dark mode", icon = Icons.Rounded.DarkMode, trailing = { RgSwitch(false, {}) })
            }
        }
    }
}

@Composable
private fun Buttons() {
    Label("Primary · HERO / LARGE / MEDIUM / SMALL")
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        RgPrimaryButton("شروع کنید", {}, size = RgButtonSize.HERO, modifier = Modifier.fillMaxWidth())
        RgPrimaryButton("ضبط ویدیو", {}, icon = Icons.Rounded.Videocam, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            RgPrimaryButton("ذخیره", {}, size = RgButtonSize.MEDIUM)
            RgSecondaryButton("لغو", {}, size = RgButtonSize.MEDIUM)
            RgOutlineButton("پیش‌نمایش", {}, size = RgButtonSize.MEDIUM)
            RgIconButton(Icons.Rounded.MoreVert, null, {}, size = 40.dp, iconSize = 20.dp)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            RgPrimaryButton("افزودن", {}, icon = Icons.Rounded.Add, size = RgButtonSize.SMALL)
            RgSecondaryButton("ویرایش", {}, icon = Icons.Rounded.Edit, size = RgButtonSize.SMALL)
            RgChip("همه", selected = true, onClick = {})
            RgChip("پیش‌نویس", selected = false, onClick = {})
        }
    }
    Label("States · disabled / loading / text")
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            RgPrimaryButton("غیرفعال", {}, enabled = false, modifier = Modifier.weight(1f))
            RgSecondaryButton("ثانویه", {}, modifier = Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
            RgSecondaryButton("غیرفعال", {}, enabled = false, size = RgButtonSize.MEDIUM)
            RgOutlineButton("غیرفعال", {}, enabled = false)
            RgTextButton("مشاهده همه", {})
            RgTextButton("حذف", {}, color = RgTheme.colors.danger)
        }
    }
    Label("Icon buttons · 40 / 44 / 48 · selected · glass")
    Row(Modifier.padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.md), verticalAlignment = Alignment.CenterVertically) {
        RgIconButton(Icons.Rounded.Search, null, {}, size = 40.dp, iconSize = 20.dp)
        RgIconButton(Icons.Rounded.Settings, null, {})
        RgIconButton(Icons.Rounded.AutoAwesome, null, {}, size = 48.dp, iconSize = 24.dp, selected = true)
        Box(Modifier.size(width = 120.dp, height = 56.dp).padding(0.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.fillMaxSize().padding(2.dp)) {
                GlassSurface(Modifier.fillMaxSize(), tint = Color(0xFF3A3050)) {}
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                RgIconButton(Icons.Rounded.FlipCameraAndroid, null, {}, glass = true, size = 40.dp, iconSize = 20.dp)
                RgIconButton(Icons.Rounded.Videocam, null, {}, glass = true, size = 40.dp, iconSize = 20.dp, enabled = false)
            }
        }
    }
    Label("Tags & badges")
    Row(Modifier.padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
        RgTag("۹:۱۶")
        RgTag("۰۲:۳۵", icon = Icons.Rounded.Videocam)
        RgTag("آماده", color = RgTheme.colors.pastelMint, contentColor = RgTheme.colors.textPrimary)
        ProBadge()
        ProBadge(text = "حرفه‌ای")
    }
}

@Composable
private fun Controls() {
    Label("Switch · on / off / disabled")
    Row(Modifier.padding(horizontal = Spacing.gutter), horizontalArrangement = Arrangement.spacedBy(Spacing.lg), verticalAlignment = Alignment.CenterVertically) {
        RgSwitch(true, {})
        RgSwitch(false, {})
        RgSwitch(true, {}, enabled = false)
        RgSwitch(false, {}, enabled = false)
    }
    Label("Segmented")
    RgSegmentedControl(listOf("۹:۱۶", "۱۶:۹", "۱:۱", "۴:۵"), "۹:۱۶", {}, { it }, modifier = Modifier.padding(horizontal = Spacing.gutter).fillMaxWidth())
    RgSegmentedControl(listOf("خودکار", "روشن", "تیره"), "تیره", {}, { it }, modifier = Modifier.padding(horizontal = Spacing.gutter))
    Label("Sliders")
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
        RgLabeledSlider("سرعت پخش", 62f, {}, valueText = "۶۲")
        RgLabeledSlider("دمای رنگ", 20f, {}, valueRange = -50f..50f, bipolar = true, valueText = "+۲۰")
        RgLabeledSlider("غیرفعال", 30f, {}, enabled = false, valueText = "۳۰")
    }
    Label("Swatches")
    ColorSwatchRow(
        listOf(Color.White, Color.Black, Palette.Lavender400, Palette.Rose400, Palette.Peach400, Palette.Mint400, Palette.Sky400, Palette.Butter400),
        Palette.Rose400,
        {},
        modifier = Modifier.padding(horizontal = Spacing.gutter),
    )
    Label("Text fields")
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
        RgTextField("", {}, placeholder = "جستجو در پروژه‌ها", leadingIcon = Icons.Rounded.Search)
        RgTextField("سلام دوستان، امروز می‌خواهیم", {}, label = "عنوان اسکریپت")
        RgTextField("abc", {}, label = "ایمیل", isError = true, supportingText = "نشانی ایمیل معتبر نیست")
    }
}

@Composable
private fun Lists() {
    SectionHeader("پروژه‌های اخیر", action = "همه", onAction = {})
    SectionHeader("قالب‌ها", subtitle = "برای شروع سریع")
    RgGroup(title = "حساب") {
        RgListItem("همگام‌سازی ابری", subtitle = "آخرین همگام‌سازی: ۵ دقیقه پیش", icon = Icons.Rounded.CloudUpload, onClick = {})
        RgDivider()
        RgListItem("زبان", subtitle = "فارسی", icon = Icons.Rounded.Language, onClick = {})
        RgDivider()
        RgListItem("حالت تیره", icon = Icons.Rounded.DarkMode, trailing = { RgSwitch(true, {}) })
        RgDivider()
        RgListItem(
            "یک عنوان بسیار طولانی برای آزمایش شکستن خط در ردیف فهرست",
            subtitle = "توضیح طولانی که باید در چند خط نمایش داده شود و بریده نشود تا کاربر همه چیز را بخواند.",
            icon = Icons.Rounded.VideoLibrary,
            trailing = { ProBadge() },
        )
    }
    RgGroup {
        RgListItem("خروج از حساب", iconTint = RgTheme.colors.danger, iconBackground = RgTheme.colors.pastelRose, icon = Icons.Rounded.Settings, onClick = {})
    }
}

@Composable
private fun States() {
    Column(Modifier.padding(horizontal = Spacing.gutter), verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
        RgCard(Modifier.fillMaxWidth()) {
            Text("در حال بارگذاری", style = MaterialTheme.typography.titleMedium, color = RgTheme.colors.textPrimary)
            Box(Modifier.height(Spacing.md))
            ShimmerBox(Modifier.fillMaxWidth().height(14.dp))
            Box(Modifier.height(Spacing.sm))
            ShimmerBox(Modifier.fillMaxWidth(0.6f).height(14.dp))
            Box(Modifier.height(Spacing.lg))
            RgProgressBar(0.62f)
        }
    }
    EmptyState(
        icon = Icons.Rounded.VideoLibrary,
        title = "هنوز پروژه‌ای ندارید",
        message = "اولین ویدیوی خود را ضبط کنید یا از یک قالب آماده شروع کنید.",
        actionText = "ساخت پروژه",
        onAction = {},
    )
}
