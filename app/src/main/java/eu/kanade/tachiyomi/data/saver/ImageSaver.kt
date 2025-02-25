package eu.kanade.tachiyomi.data.saver

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.util.storage.DiskUtil
import eu.kanade.tachiyomi.util.storage.cacheImageDir
import eu.kanade.tachiyomi.util.storage.getUriCompat
import eu.kanade.tachiyomi.util.system.ImageUtil
import okio.IOException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

class ImageSaver(
    val context: Context,
) {

    @SuppressLint("InlinedApi")
    fun save(image: Image): Uri {
        val data = image.data

        val type = ImageUtil.findImageType(data) ?: throw Exception("Not an image")
        val filename = DiskUtil.buildValidFilename("${image.name}.${type.extension}")

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return save(data(), image.location.directory(context), filename)
        }

        if (image.location !is Location.Pictures) {
            return save(data(), image.location.directory(context), filename)
        }

        val pictureDir =
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

        val contentValues = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, image.name)
            put(MediaStore.Images.Media.MIME_TYPE, type.mime)
            put(
                MediaStore.Images.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_PICTURES}/${context.getString(R.string.app_name)}/" +
                    (image.location as Location.Pictures).relativePath,
            )
        }

        val picture = context.contentResolver.insert(
            pictureDir,
            contentValues,
        ) ?: throw IOException("Couldn't create file")

        data().use { input ->
            @Suppress("BlockingMethodInNonBlockingContext")
            context.contentResolver.openOutputStream(picture, "w").use { output ->
                input.copyTo(output!!)
            }
        }

        DiskUtil.scanMedia(context, picture)

        return picture
    }

    private fun save(inputStream: InputStream, directory: File, filename: String): Uri {
        directory.mkdirs()

        val destFile = File(directory, filename)

        inputStream.use { input ->
            destFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        DiskUtil.scanMedia(context, destFile)

        return destFile.getUriCompat(context)
    }
}

sealed class Image(
    open val name: String,
    open val location: Location,
) {
    data class Cover(
        val bitmap: Bitmap,
        override val name: String,
        override val location: Location,
    ) : Image(name, location)

    data class Page(
        val inputStream: () -> InputStream,
        override val name: String,
        override val location: Location,
    ) : Image(name, location)

    val data: () -> InputStream
        get() {
            return when (this) {
                is Cover -> {
                    {
                        val baos = ByteArrayOutputStream()
                        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, baos)
                        ByteArrayInputStream(baos.toByteArray())
                    }
                }
                is Page -> inputStream
            }
        }
}

sealed class Location {
    data class Pictures private constructor(val relativePath: String) : Location() {
        companion object {
            fun create(relativePath: String = ""): Pictures {
                return Pictures(relativePath)
            }
        }
    }

    object Cache : Location()

    fun directory(context: Context): File {
        return when (this) {
            Cache -> context.cacheImageDir
            is Pictures -> {
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    context.getString(R.string.app_name),
                )
                if (relativePath.isNotEmpty()) {
                    return File(
                        file,
                        relativePath,
                    )
                }
                file
            }
        }
    }
}
