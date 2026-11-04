package com.music.bitchord.desktop

import com.music.bitchord.playback.smart.AnalysisLog
import com.music.bitchord.playback.smart.NativeAnalysisLibrary
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/** Unpacks what the analyser needs out of the jar, once per install. */
internal object DesktopAnalysisRuntime {

    private val started = AtomicBoolean(false)

    /** Where unpacked resources live, alongside the desktop log. */
    private val home: Path by lazy {
        val base = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
            ?: "${System.getProperty("user.home")}/.local/share"
        Path.of(base, "bitchord", "analysis")
    }

    /** Where unpacked resources and cached analyses live. */
    fun analysisHome(): java.io.File = home.toFile().also { Files.createDirectories(home) }

    /** True once the native analyser is loaded and usable. */
    val available: Boolean get() = ensureStarted().let { NativeAnalysisLibrary.available }

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        AnalysisLog.install { message, error ->
            DesktopTrackLog.log(
                when {
                    error == null -> message
                    else -> "$message: ${error.message}" + hintFor(error)
                },
            )
        }
        NativeAnalysisLibrary.install {
            // `mapLibraryName` gives `libbitchord_analysis.so` on Linux and `bitchord_analysis.dll`
            // on Windows.
            val fileName = System.mapLibraryName(NativeAnalysisLibrary.LIBRARY_NAME)
            System.load(unpack("/native/$fileName", fileName).toString())
        }
    }

    /** A sentence worth adding to a failure whose real cause is off-screen. */
    private fun hintFor(error: Throwable): String {
        if (!DesktopPlatform.isWindows) return ""
        val text = generateSequence(error, Throwable::cause).mapNotNull { it.message }.joinToString(" ")
        val missingRuntime = "dependent libraries" in text ||
            "OrtSession" in text ||
            "onnxruntime" in text && "UnsatisfiedLink" in error::class.java.simpleName
        return if (missingRuntime) {
            " — install the Microsoft Visual C++ Redistributable (x64); ONNX Runtime is built against it"
        } else {
            ""
        }
    }

    /**
     * Unpacks and loads a native library that travels in the jar.
     *
     * The analyser's own load goes through [NativeAnalysisLibrary]; this is the
     * same unpack for anything else that ships beside it, so there is one place
     * that knows where the jar keeps them.
     */
    fun loadNative(name: String) {
        val fileName = System.mapLibraryName(name)
        System.load(unpack("/native/$fileName", fileName).toString())
    }

    /** The absolute path of a model, unpacked on first use. */
    fun modelPath(asset: String): String = unpack("/models/$asset", asset).toString()

    /**
     * Copies a classpath resource to [name] under [home], skipping the copy when the file already
     * there is the same size and no older than the resource.
     *
     * Size alone is not enough: a rebuilt library can come out byte-for-byte the same length as the
     * one it replaces, and the stale copy was then loaded forever — a native fix that never ran.
     */
    @Synchronized // fork: the window and audio sides load the same library; two copies at once could move over a loaded DLL
    private fun unpack(resource: String, name: String): Path {
        Files.createDirectories(home)
        val target = home.resolve(name)
        val stream = javaClass.getResourceAsStream(resource)
            ?: error("$resource is missing from this build")
        stream.use { input ->
            val connection = javaClass.getResource(resource)?.openConnection()
            val expected = connection?.contentLengthLong ?: -1L
            val builtAt = connection?.lastModified ?: 0L
            // fork: the jar entry's CRC, kept beside the copy, decides; size and date could not. A
            // native fix that kept the library's size, built in a container whose zip entries carry
            // UTC times read back as local ones, looked "same size, not older" and the old library
            // was loaded on (seen 2026-11-04: the window frame change never ran). Outside a jar
            // (a run from build output) there is no CRC, and the old rule stays.
            val crc = (connection as? java.net.JarURLConnection)?.jarEntry?.crc?.takeIf { it >= 0 }
            val stamp = home.resolve("$name.crc")
            val same = if (crc != null) {
                Files.exists(target) && Files.size(target) == expected &&
                    runCatching { Files.readString(stamp).trim() }.getOrNull() == crc.toString()
            } else {
                Files.exists(target) && expected > 0 && Files.size(target) == expected &&
                    Files.getLastModifiedTime(target).toMillis() >= builtAt
            }
            if (same) return target
            val partial = home.resolve("$name.partial")
            Files.copy(input, partial, StandardCopyOption.REPLACE_EXISTING)
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            if (crc != null) runCatching { Files.writeString(stamp, crc.toString()) } // fork
        }
        return target
    }
}
