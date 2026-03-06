package com.example.randomdelete

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

object LivePhotoUtils {
    data class LivePhotoInfo(
        val isLivePhoto: Boolean,
        val videoOffsetBytes: Long?
    )

    fun detectLivePhoto(context: Context, uri: Uri): LivePhotoInfo {
        val xmp = readXmp(context, uri) ?: return LivePhotoInfo(isLivePhoto = false, videoOffsetBytes = null)

        val hasMarker = LIVE_MARKERS.any { marker ->
            xmp.contains(marker, ignoreCase = true)
        }
        if (!hasMarker) {
            return LivePhotoInfo(isLivePhoto = false, videoOffsetBytes = null)
        }

        val offset = parseVideoOffset(xmp)
        return LivePhotoInfo(isLivePhoto = true, videoOffsetBytes = offset)
    }

    fun extractLiveVideoToCache(
        context: Context,
        photoUri: Uri,
        info: LivePhotoInfo
    ): Uri? {
        if (!info.isLivePhoto) return null

        val outFile = buildCacheFile(context, photoUri, info.videoOffsetBytes)
        if (outFile.exists() && outFile.length() > 0L) {
            return Uri.fromFile(outFile)
        }

        val totalLength = context.contentResolver
            .openAssetFileDescriptor(photoUri, "r")
            ?.use { afd -> afd.length }
            ?: -1L

        val copied = if (info.videoOffsetBytes != null && totalLength > 0L) {
            val start = totalLength - info.videoOffsetBytes
            if (start > 0L) {
                copyFromOffset(context, photoUri, start, outFile)
            } else {
                false
            }
        } else {
            false
        }

        if (copied && outFile.length() > 0L) {
            return Uri.fromFile(outFile)
        }

        val bytes = context.contentResolver.openInputStream(photoUri)?.use { it.readBytes() } ?: return null
        val mp4Start = findMp4Start(bytes) ?: return null

        FileOutputStream(outFile).use { out ->
            out.write(bytes, mp4Start, bytes.size - mp4Start)
        }
        return if (outFile.length() > 0L) Uri.fromFile(outFile) else null
    }

    private fun readXmp(context: Context, uri: Uri): String? {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            runCatching {
                ExifInterface(input).getAttribute(ExifInterface.TAG_XMP)
            }.getOrNull()
        }
    }

    private fun parseVideoOffset(xmp: String): Long? {
        val regexes = listOf(
            Regex("MicroVideoOffset=\\\"(\\d+)\\\"", RegexOption.IGNORE_CASE),
            Regex("GCamera:MicroVideoOffset=\\\"(\\d+)\\\"", RegexOption.IGNORE_CASE),
            Regex("MotionPhotoOffset=\\\"(\\d+)\\\"", RegexOption.IGNORE_CASE)
        )

        for (regex in regexes) {
            val value = regex.find(xmp)?.groupValues?.getOrNull(1)?.toLongOrNull()
            if (value != null && value > 0L) return value
        }
        return null
    }

    private fun copyFromOffset(context: Context, uri: Uri, startOffset: Long, outFile: File): Boolean {
        return try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                skipFully(input, startOffset)
                FileOutputStream(outFile).use { output ->
                    input.copyTo(output)
                }
            }
            outFile.exists() && outFile.length() > 0L
        } catch (_: Exception) {
            false
        }
    }

    private fun skipFully(input: InputStream, bytesToSkip: Long) {
        var remaining = bytesToSkip
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped <= 0L) {
                if (input.read() == -1) return
                remaining -= 1
            } else {
                remaining -= skipped
            }
        }
    }

    private fun findMp4Start(bytes: ByteArray): Int? {
        val from = maxOf(4, bytes.size - 16 * 1024 * 1024)
        val to = bytes.size - 8
        for (i in from..to) {
            val isFtyp =
                bytes[i] == 'f'.code.toByte() &&
                    bytes[i + 1] == 't'.code.toByte() &&
                    bytes[i + 2] == 'y'.code.toByte() &&
                    bytes[i + 3] == 'p'.code.toByte()
            if (!isFtyp) continue

            val boxStart = i - 4
            if (boxStart >= 0) {
                return boxStart
            }
        }
        return null
    }

    private fun buildCacheFile(context: Context, uri: Uri, offset: Long?): File {
        val dir = File(context.cacheDir, "live_photo_videos")
        if (!dir.exists()) dir.mkdirs()

        val key = "${uri.toString().hashCode().toUInt().toString(16)}_${offset ?: 0L}.mp4"
        return File(dir, key)
    }

    private val LIVE_MARKERS = listOf(
        "MicroVideo",
        "MotionPhoto",
        "GCamera:MotionPhoto",
        "GCamera:MicroVideo",
        "Camera:MotionPhoto",
        "vivo",
        "LivePhoto"
    )
}
