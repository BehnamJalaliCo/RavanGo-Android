package com.ravango.engine.editor.ops

import android.net.Uri
import com.ravango.core.common.device.StorageInfo
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Where the editor keeps generated media for a project (reversed renditions, stills, extracted audio, voice-overs). */
@Singleton
class ProjectFiles @Inject constructor(private val storage: StorageInfo) {
    fun projectDir(projectId: String): File = File(storage.mediaDir, "projects/$projectId").apply { mkdirs() }
    fun reversedDir(projectId: String): File = File(projectDir(projectId), "reversed").apply { mkdirs() }
    fun stillsDir(projectId: String): File = File(projectDir(projectId), "stills").apply { mkdirs() }
    fun audioDir(projectId: String): File = File(projectDir(projectId), "audio").apply { mkdirs() }
    fun importsDir(projectId: String): File = File(projectDir(projectId), "imports").apply { mkdirs() }
    fun tempDir(): File = File(storage.cacheMediaDir, "editor").apply { mkdirs() }
    val exportsDir: File get() = storage.exportsDir

    companion object {
        fun uriOf(file: File): String = Uri.fromFile(file).toString()
    }
}
