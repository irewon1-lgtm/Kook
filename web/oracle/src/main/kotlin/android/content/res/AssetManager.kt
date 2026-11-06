package android.content.res

import java.io.File
import java.io.InputStream

/** Maps original asset reads to a supplied read-only asset directory. */
class AssetManager(private val directory: File) {
    fun open(name: String): InputStream = File(directory, name).inputStream()
}
