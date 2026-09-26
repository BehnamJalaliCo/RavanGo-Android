package com.ravango.engine.beauty.mesh

import android.content.Context
import com.ravango.core.common.log.RgLog
import com.ravango.engine.beauty.makeup.MakeupAtlas

/**
 * Process-wide, immutable face assets: the canonical mesh (parsed once from `assets/face_mesh/`), its topology and
 * the UV-space makeup atlas (generated once on a background thread). The GL thread polls [ready] and never blocks.
 */
internal object FaceAssets {

    const val MESH_ASSET = "face_mesh/canonical_face_model.obj"

    class Loaded(
        val model: FaceMeshModel,
        val topology: FaceTopology,
        val atlas: MakeupAtlas.Textures,
    )

    @Volatile var loaded: Loaded? = null; private set
    @Volatile var failed: Boolean = false; private set
    @Volatile private var started = false

    val ready: Boolean get() = loaded != null

    fun prepare(context: Context) {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        val app = context.applicationContext
        Thread({
            try {
                val t0 = System.nanoTime()
                val model = app.assets.open(MESH_ASSET).bufferedReader().use { ObjParser.parse(it.readText()) }
                val topology = FaceTopology(model)
                val atlas = MakeupAtlas.generate(model)
                loaded = Loaded(model, topology, atlas)
                RgLog.i(TAG, "Face mesh + makeup atlas ready in ${(System.nanoTime() - t0) / 1_000_000} ms")
            } catch (t: Throwable) {
                failed = true
                RgLog.e(TAG, "Face assets unavailable", t)
            }
        }, "rg-face-assets").apply { isDaemon = true; priority = Thread.NORM_PRIORITY - 1 }.start()
    }

    private const val TAG = "FaceAssets"
}
