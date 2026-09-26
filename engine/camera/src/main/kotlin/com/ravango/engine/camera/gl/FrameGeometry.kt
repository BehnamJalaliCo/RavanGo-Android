package com.ravango.engine.camera.gl

import com.ravango.core.model.LensFacing

/**
 * 2D affine transform: x' = a·x + b·y + tx, y' = c·x + d·y + ty. Used for texture-coordinate mapping.
 */
data class Affine(
    val a: Float = 1f,
    val b: Float = 0f,
    val c: Float = 0f,
    val d: Float = 1f,
    val tx: Float = 0f,
    val ty: Float = 0f,
) {
    fun mapX(x: Float, y: Float): Float = a * x + b * y + tx
    fun mapY(x: Float, y: Float): Float = c * x + d * y + ty

    /** Returns the transform that applies `this` first, then [next]. */
    infix fun then(next: Affine): Affine = Affine(
        a = next.a * a + next.b * c,
        b = next.a * b + next.b * d,
        c = next.c * a + next.d * c,
        d = next.c * b + next.d * d,
        tx = next.a * tx + next.b * ty + next.tx,
        ty = next.c * tx + next.d * ty + next.ty,
    )

    /** Writes a column-major 4×4 matrix usable as a GL texture matrix. */
    fun toGlMatrix(out: FloatArray) {
        out.fill(0f)
        out[0] = a; out[1] = c
        out[4] = b; out[5] = d
        out[10] = 1f
        out[12] = tx; out[13] = ty
        out[15] = 1f
    }

    companion object {
        val IDENTITY = Affine()

        /** GL texture space (origin bottom-left) ↔ image space (origin top-left). Self-inverse. */
        val FLIP_Y = Affine(1f, 0f, 0f, -1f, 0f, 1f)

        /** Horizontal mirror in normalized coordinates. Self-inverse. */
        val MIRROR_X = Affine(-1f, 0f, 0f, 1f, 1f, 0f)

        /**
         * For an image that is the source rotated clockwise by [degrees], maps normalized image-space
         * coordinates of the rotated (displayed/upright) image back to the source image.
         */
        fun inverseRotation(degrees: Int): Affine = when (normalize(degrees)) {
            90 -> Affine(a = 0f, b = 1f, c = -1f, d = 0f, tx = 0f, ty = 1f)
            180 -> Affine(a = -1f, b = 0f, c = 0f, d = -1f, tx = 1f, ty = 1f)
            270 -> Affine(a = 0f, b = -1f, c = 1f, d = 0f, tx = 1f, ty = 0f)
            else -> IDENTITY
        }

        fun normalize(degrees: Int): Int = ((degrees % 360) + 360) % 360
    }
}

/** Normalized crop inside an image (image space, origin top-left). */
data class CropRect(val offsetX: Float, val offsetY: Float, val scaleX: Float, val scaleY: Float) {
    fun asAffine(): Affine = Affine(a = scaleX, d = scaleY, tx = offsetX, ty = offsetY)

    companion object {
        val FULL = CropRect(0f, 0f, 1f, 1f)
    }
}

data class FitRect(val left: Float, val top: Float, val width: Float, val height: Float)

/**
 * Orientation, mirroring and aspect-crop math for the camera GL pipeline. Pure and unit tested.
 *
 * Conventions: device orientation follows OrientationEventListener (0 = natural, 90 = left side up …).
 * The pipeline produces frames upright relative to gravity; the portrait-locked preview rotates them back.
 */
object FrameGeometry {

    /** Clockwise rotation that turns the sensor image upright for the given device orientation. */
    fun rotationCw(sensorOrientation: Int, deviceOrientation: Int, facing: LensFacing): Int {
        val device = snap(deviceOrientation)
        return if (facing == LensFacing.FRONT) {
            Affine.normalize(sensorOrientation - device)
        } else {
            Affine.normalize(sensorOrientation + device)
        }
    }

    /** Snaps any angle to 0/90/180/270. */
    fun snap(degrees: Int): Int = Affine.normalize(((Affine.normalize(degrees) + 45) / 90) * 90)

    /** Dimensions of the buffer after rotating it by [rotationCw]. */
    fun rotatedSize(width: Int, height: Int, rotationCw: Int): Pair<Int, Int> =
        if (Affine.normalize(rotationCw) % 180 == 90) height to width else width to height

    /** Centered crop of an image with aspect [sourceAspect] (w/h) to [targetAspect]. */
    fun centerCrop(sourceAspect: Float, targetAspect: Float): CropRect {
        if (sourceAspect <= 0f || targetAspect <= 0f) return CropRect.FULL
        return if (sourceAspect > targetAspect) {
            val sx = targetAspect / sourceAspect
            CropRect((1f - sx) / 2f, 0f, sx, 1f)
        } else {
            val sy = sourceAspect / targetAspect
            CropRect(0f, (1f - sy) / 2f, 1f, sy)
        }
    }

    /**
     * Image-space mapping from the output frame to the camera buffer: un-mirror, un-crop, un-rotate.
     */
    fun outputToBufferImage(rotationCw: Int, crop: CropRect, mirror: Boolean): Affine {
        var t = if (mirror) Affine.MIRROR_X else Affine.IDENTITY
        t = t then crop.asAffine()
        return t then Affine.inverseRotation(rotationCw)
    }

    /**
     * Texture matrix for the normalize pass, in GL texture space (origin bottom-left) on both sides.
     * The SurfaceTexture transform must be applied after this (texMatrix = ST × this).
     */
    fun outputToBufferGl(rotationCw: Int, crop: CropRect, mirror: Boolean): Affine =
        Affine.FLIP_Y then outputToBufferImage(rotationCw, crop, mirror) then Affine.FLIP_Y

    /** Clockwise rotation used to show a gravity-upright frame on a screen locked to the natural orientation. */
    fun previewRotationCw(deviceOrientation: Int): Int = Affine.normalize(360 - snap(deviceOrientation))

    /** GL texture matrix mapping the preview viewport to the frame, for a frame displayed rotated by [rotationCw]. */
    fun displayToFrameGl(rotationCw: Int, mirror: Boolean = false): Affine {
        var t = Affine.FLIP_Y then Affine.inverseRotation(rotationCw)
        if (mirror) t = t then Affine.MIRROR_X
        return t then Affine.FLIP_Y
    }

    /** Mirror-only matrix (GL space) used when the encoder must receive the un-mirrored image. */
    fun encoderMatrixGl(flipHorizontally: Boolean): Affine = if (flipHorizontally) Affine.MIRROR_X else Affine.IDENTITY

    /** Displayed dimensions of a frame of [width]×[height] rotated by [rotationCw]. */
    fun displayedSize(width: Int, height: Int, rotationCw: Int): Pair<Int, Int> = rotatedSize(width, height, rotationCw)

    /** Letterbox ("contain") fit of content into a container. */
    fun fit(contentWidth: Float, contentHeight: Float, containerWidth: Float, containerHeight: Float): FitRect {
        if (contentWidth <= 0f || contentHeight <= 0f || containerWidth <= 0f || containerHeight <= 0f) {
            return FitRect(0f, 0f, containerWidth, containerHeight)
        }
        val scale = minOf(containerWidth / contentWidth, containerHeight / contentHeight)
        val w = contentWidth * scale
        val h = contentHeight * scale
        return FitRect((containerWidth - w) / 2f, (containerHeight - h) / 2f, w, h)
    }

    /**
     * Maps a normalized point inside the displayed preview frame (image space) to the camera buffer (image space).
     * Used for tap-to-focus/meter.
     */
    fun displayPointToBuffer(
        x: Float,
        y: Float,
        previewRotationCw: Int,
        rotationCw: Int,
        crop: CropRect,
        mirror: Boolean,
    ): Pair<Float, Float> {
        val toFrame = Affine.inverseRotation(previewRotationCw)
        val fx = toFrame.mapX(x, y)
        val fy = toFrame.mapY(x, y)
        val toBuffer = outputToBufferImage(rotationCw, crop, mirror)
        return toBuffer.mapX(fx, fy) to toBuffer.mapY(fx, fy)
    }
}
