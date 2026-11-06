package android.content

import android.content.res.AssetManager
import java.io.File

/** JVM-only filesystem adapter. Original Android source remains unchanged. */
class Context(val filesDir: File, assetsDirectory: File) {
    val applicationContext: Context get() = this
    val assets = AssetManager(assetsDirectory)
}
