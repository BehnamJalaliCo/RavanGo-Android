package com.ravango.engine.editor.export

import androidx.media3.transformer.Composition
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Runs a [Transformer] export as a cancellable suspend call.
 *
 * The transformer is created by [create] on the main thread (its application looper), progress is polled with
 * [Transformer.getProgress] every [pollMs], and cancelling the coroutine cancels the export.
 */
internal suspend fun runTransformer(
    create: () -> Transformer,
    composition: Composition,
    outputPath: String,
    pollMs: Long = 200,
    onProgress: (Float) -> Unit,
): ExportResult = withContext(Dispatchers.Main.immediate) {
    val transformer = create()
    val done = CompletableDeferred<ExportResult>()
    val listener = object : Transformer.Listener {
        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
            done.complete(exportResult)
        }

        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
            done.completeExceptionally(exportException)
        }
    }
    transformer.addListener(listener)
    coroutineScope {
        transformer.start(composition, outputPath)
        val poller = launch {
            val holder = ProgressHolder()
            while (isActive) {
                if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE) onProgress(holder.progress / 100f)
                delay(pollMs)
            }
        }
        try {
            done.await()
        } catch (e: CancellationException) {
            transformer.cancel()
            throw e
        } finally {
            poller.cancel()
            transformer.removeListener(listener)
        }
    }
}
