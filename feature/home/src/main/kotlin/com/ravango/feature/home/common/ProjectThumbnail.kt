package com.ravango.feature.home.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.decode.VideoFrameDecoder
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import com.ravango.core.data.repository.ProjectRepository
import com.ravango.core.designsystem.theme.RgTheme
import com.ravango.core.media.toUri
import com.ravango.core.model.MediaAsset
import com.ravango.core.model.MediaKind
import com.ravango.core.model.Project
import java.io.File

/** What to draw for a project preview: a still image, or a frame grabbed from a video. */
data class ThumbnailSource(val uri: String, val isVideo: Boolean)

/** Prefers the stored thumbnail, then the first visual asset of the project. */
fun thumbnailFor(project: Project, firstVisualAsset: MediaAsset?): ThumbnailSource? {
    project.thumbnailPath?.takeIf { it.isNotBlank() && (!it.startsWith("/") || File(it).exists()) }?.let { return ThumbnailSource(it, isVideo = false) }
    val asset = firstVisualAsset ?: return null
    return when (asset.kind) {
        MediaKind.VIDEO -> ThumbnailSource(asset.uri, isVideo = true)
        MediaKind.IMAGE -> ThumbnailSource(asset.uri, isVideo = false)
        MediaKind.AUDIO -> null
    }
}

/** First non-audio asset per project (assets are expected newest-first or insertion order; we take the oldest). */
fun firstVisualAssets(assets: List<MediaAsset>): Map<String, MediaAsset> =
    assets.asSequence()
        .filter { it.projectId != null && it.kind != MediaKind.AUDIO && it.deletedAt == null }
        .sortedBy { it.createdAt }
        .groupBy { it.projectId!! }
        .mapValues { it.value.first() }

/** Convenience for view models: observe all visual assets as a projectId → first asset map. */
fun ProjectRepository.observeFirstVisualAssets() = kotlinx.coroutines.flow.flow {
    observeAllAssets(null).collect { emit(firstVisualAssets(it)) }
}

/**
 * Project preview with a pastel placeholder while loading or when there is nothing to show
 * (audio-only projects get a waveform glyph).
 */
@Composable
fun ProjectThumbnail(
    source: ThumbnailSource?,
    accentSeed: String,
    modifier: Modifier = Modifier,
    audioOnly: Boolean = false,
) {
    val colors = RgTheme.colors
    val tone = remember(accentSeed, colors) { colors.tones.forSeed(accentSeed) }
    val pastel = remember(tone, colors) {
        // Tone wash with a soft top-lit sheen: reads as a designed placeholder, not an empty box.
        if (colors.isDark) {
            // Dark tone containers are deep; lift them with a wash of the tone's own light content color.
            Brush.linearGradient(listOf(tone.content.copy(alpha = 0.26f).compositeOver(tone.container), tone.container))
        } else {
            Brush.linearGradient(listOf(tone.container, colors.tones.forSeed(accentSeed + "b").container))
        }
    }
    var failed by remember(source) { mutableStateOf(false) }
    Box(modifier.background(pastel), contentAlignment = Alignment.Center) {
        if (source == null || failed) {
            Icon(
                if (audioOnly) Icons.Rounded.GraphicEq else Icons.Rounded.Movie,
                contentDescription = null,
                tint = tone.content.copy(alpha = 0.7f),
                modifier = Modifier.size(34.dp),
            )
        }
        if (source != null && !failed) {
            val context = LocalContext.current
            val request = remember(source) {
                ImageRequest.Builder(context)
                    .data(source.uri.toUri())
                    .crossfade(220)
                    .apply { if (source.isVideo) decoderFactory(VideoFrameDecoder.Factory()).videoFrameMillis(800) }
                    .memoryCacheKey("thumb:${source.uri}")
                    .build()
            }
            AsyncImage(
                model = request,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { failed = true },
            )
        }
    }
}
