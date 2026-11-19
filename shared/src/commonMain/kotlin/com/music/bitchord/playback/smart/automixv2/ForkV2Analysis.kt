package com.music.bitchord.playback.smart.automixv2

/**
 * One song's analysis, field for field the extension's (BitChord web's TrackAnalysis plus
 * contentEndSec and musicVideo), with the same JSON names so a saved analysis reads the same
 * on both. A missing value is NaN ("null" in the JSON). A port of the phone's Analysis.java;
 * the fork's own [com.music.bitchord.playback.smart.TrackAnalysis] is a different shape (see
 * [ForkV2FromTrackAnalysis]).
 */
class ForkV2Analysis {
    var fileKey: String? = null
    var durationSec = Double.NaN
    var contentEndSec = Double.NaN

    /** The mix-in grid (the song's opening). */
    var bpm = Double.NaN
    var beatIntervalSec = Double.NaN
    var firstDownbeatSec = Double.NaN
    var gridConfidence = 0.0

    /** The mix-out grid (the song's end); null when no beat was found there. */
    var tailGrid: LocalGrid? = null

    /** False: the field was never filled in (an analysis from before tail grids). */
    var tailGridKnown = true
    var headBeats: BeatSide? = null
    var tailBeats: BeatSide? = null
    var introEndSec = Double.NaN
    var outroStartSec = Double.NaN
    var energy = 0.0
    var energyCurve = DoubleArray(0)

    /** 0: unknown. */
    var camelotNum = 0

    /** 'A' minor, 'B' major. */
    var camelotLetter = '\u0000'
    var keyName: String? = null
    var keyConfidence = 0.0
    var vocals: MutableList<VocalWindow> = ArrayList()
    var musicVideo = false

    /**
     * Automix v2's evidence (the extension's host.js): the share of the low end under 60 Hz against
     * 60-120 Hz (log ratio), the bass level over time (of its usual), the whole-song pitch profile,
     * the pitch profile over time and the 100 ms K-weighted loudness (dB). NaN / null: unmeasured.
     */
    var subBassRatio = Double.NaN
    var bassCurve: Curve? = null
    var loudnessCurve: Curve? = null
    var chroma: DoubleArray? = null
    var chromaCurve: ChromaCurve? = null

    /** A stand-in for a song with no analysis (the planner's stub). */
    var stub = false
    var analysisVersion: String? = VERSION

    class LocalGrid {
        var bpm = 0.0
        var beatIntervalSec = 0.0
        var firstDownbeatSec = 0.0
        var gridConfidence = 0.0
    }

    class BeatSide {
        var beats = DoubleArray(0)
        var downbeats = DoubleArray(0)
        var bpm = 0.0
        var confidence = 0.0
    }

    /** Values every [step] seconds from [start]. */
    class Curve {
        var start = 0.0
        var step = 0.0
        var values = DoubleArray(0)
    }

    /** 12-bin pitch profiles every [step] seconds from [start] (a null frame: none there), each with its weight. */
    class ChromaCurve {
        var start = 0.0
        var step = 0.0
        var values: Array<DoubleArray?> = arrayOf()
        var weights = DoubleArray(0)
    }

    class VocalWindow {
        var startSec = 0.0
        var fps = 0.0
        var curve = FloatArray(0)
    }

    fun copy(): ForkV2Analysis {
        val a = ForkV2Analysis()
        a.fileKey = fileKey
        a.durationSec = durationSec
        a.contentEndSec = contentEndSec
        a.bpm = bpm
        a.beatIntervalSec = beatIntervalSec
        a.firstDownbeatSec = firstDownbeatSec
        a.gridConfidence = gridConfidence
        a.tailGrid = tailGrid
        a.tailGridKnown = tailGridKnown
        a.headBeats = headBeats
        a.tailBeats = tailBeats
        a.introEndSec = introEndSec
        a.outroStartSec = outroStartSec
        a.energy = energy
        a.energyCurve = energyCurve
        a.camelotNum = camelotNum
        a.camelotLetter = camelotLetter
        a.keyName = keyName
        a.keyConfidence = keyConfidence
        a.vocals = vocals
        a.musicVideo = musicVideo
        a.subBassRatio = subBassRatio
        a.bassCurve = bassCurve
        a.loudnessCurve = loudnessCurve
        a.chroma = chroma
        a.chromaCurve = chromaCurve
        a.stub = stub
        a.analysisVersion = analysisVersion
        return a
    }

    // ---- JSON ----

    fun toJson(): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        m["fileKey"] = fileKey
        m["durationSec"] = durationSec
        m["contentEndSec"] = contentEndSec
        m["bpm"] = bpm
        m["beatIntervalSec"] = beatIntervalSec
        m["firstDownbeatSec"] = firstDownbeatSec
        m["gridConfidence"] = gridConfidence
        if (tailGridKnown) m["tailGrid"] = tailGrid?.let { grid(it) }
        m["headBeats"] = side(headBeats)
        m["tailBeats"] = side(tailBeats)
        m["introEndSec"] = introEndSec
        m["outroStartSec"] = outroStartSec
        m["energy"] = energy
        m["energyCurve"] = energyCurve
        if (camelotNum > 0) {
            val c = LinkedHashMap<String, Any?>()
            c["num"] = camelotNum
            c["letter"] = camelotLetter.toString()
            m["camelot"] = c
        } else {
            m["camelot"] = null
        }
        m["keyName"] = keyName
        m["keyConfidence"] = keyConfidence
        val v = ArrayList<Any?>()
        for (w in vocals) {
            val x = LinkedHashMap<String, Any?>()
            x["startSec"] = w.startSec
            x["fps"] = w.fps
            x["curve"] = w.curve
            v.add(x)
        }
        m["vocals"] = v
        m["musicVideo"] = musicVideo
        if (!subBassRatio.isNaN()) m["subBassRatio"] = subBassRatio
        bassCurve?.let { m["bassCurve"] = curve(it) }
        chroma?.let { m["chroma"] = it }
        chromaCurve?.let { cc ->
            val c = LinkedHashMap<String, Any?>()
            c["start"] = cc.start
            c["step"] = cc.step
            val frames = ArrayList<Any?>()
            for (f in cc.values) frames.add(f)
            c["values"] = frames
            c["weights"] = cc.weights
            m["chromaCurve"] = c
        }
        loudnessCurve?.let { m["loudnessCurve"] = curve(it) }
        m["analysisVersion"] = analysisVersion
        return m
    }

    companion object {
        /** Bumped whenever the analysis changes: a saved one of another version is redone. */
        const val VERSION = "ayham.3"

        private fun curve(c: Curve): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["start"] = c.start
            m["step"] = c.step
            m["values"] = c.values
            return m
        }

        @Suppress("UNCHECKED_CAST")
        private fun curveOf(o: Any?): Curve? {
            if (o !is Map<*, *>) return null
            val m = o as Map<String, Any?>
            val c = Curve()
            c.start = zero(ForkV2Json.num(m, "start"))
            c.step = ForkV2Json.num(m, "step")
            val values = ForkV2Json.nums(m["values"])
            if (values == null || !(c.step > 0)) return null
            c.values = values
            return c
        }

        @Suppress("UNCHECKED_CAST")
        private fun chromaCurveOf(o: Any?): ChromaCurve? {
            if (o !is Map<*, *>) return null
            val m = o as Map<String, Any?>
            val frames = m["values"] as? List<Any?> ?: return null
            val c = ChromaCurve()
            c.start = zero(ForkV2Json.num(m, "start"))
            c.step = ForkV2Json.num(m, "step")
            c.values = Array(frames.size) { i -> ForkV2Json.nums(frames[i]) }
            val weights = ForkV2Json.nums(m["weights"])
            if (weights == null || weights.size < c.values.size || !(c.step > 0)) return null
            c.weights = weights
            return c
        }

        private fun grid(g: LocalGrid): Map<String, Any?> {
            val m = LinkedHashMap<String, Any?>()
            m["bpm"] = g.bpm
            m["beatIntervalSec"] = g.beatIntervalSec
            m["firstDownbeatSec"] = g.firstDownbeatSec
            m["gridConfidence"] = g.gridConfidence
            return m
        }

        private fun side(s: BeatSide?): Map<String, Any?>? {
            if (s == null) return null
            val m = LinkedHashMap<String, Any?>()
            m["beats"] = s.beats
            m["downbeats"] = s.downbeats
            m["bpm"] = s.bpm
            m["confidence"] = s.confidence
            return m
        }

        @Suppress("UNCHECKED_CAST")
        fun fromJson(m: Map<String, Any?>): ForkV2Analysis {
            val a = ForkV2Analysis()
            a.fileKey = m["fileKey"] as String?
            a.durationSec = ForkV2Json.num(m, "durationSec")
            a.contentEndSec = ForkV2Json.num(m, "contentEndSec")
            a.bpm = ForkV2Json.num(m, "bpm")
            a.beatIntervalSec = ForkV2Json.num(m, "beatIntervalSec")
            a.firstDownbeatSec = ForkV2Json.num(m, "firstDownbeatSec")
            a.gridConfidence = zero(ForkV2Json.num(m, "gridConfidence"))
            a.tailGridKnown = m.containsKey("tailGrid")
            val tg = m["tailGrid"]
            if (tg is Map<*, *>) {
                val g = tg as Map<String, Any?>
                val t = LocalGrid()
                t.bpm = ForkV2Json.num(g, "bpm")
                t.beatIntervalSec = ForkV2Json.num(g, "beatIntervalSec")
                t.firstDownbeatSec = ForkV2Json.num(g, "firstDownbeatSec")
                t.gridConfidence = zero(ForkV2Json.num(g, "gridConfidence"))
                a.tailGrid = t
            }
            a.headBeats = sideOf(m["headBeats"])
            a.tailBeats = sideOf(m["tailBeats"])
            a.introEndSec = ForkV2Json.num(m, "introEndSec")
            a.outroStartSec = ForkV2Json.num(m, "outroStartSec")
            a.energy = zero(ForkV2Json.num(m, "energy"))
            a.energyCurve = ForkV2Json.nums(m["energyCurve"]) ?: DoubleArray(0)
            val cam = m["camelot"]
            if (cam is Map<*, *>) {
                val c = cam as Map<String, Any?>
                a.camelotNum = ForkV2Json.num(c, "num").toInt()
                val letter = c["letter"] as String?
                a.camelotLetter = if (letter.isNullOrEmpty()) '\u0000' else letter[0]
            }
            a.keyName = m["keyName"] as String?
            a.keyConfidence = zero(ForkV2Json.num(m, "keyConfidence"))
            val vs = m["vocals"]
            if (vs is List<*>) {
                for (o in vs) {
                    val x = o as Map<String, Any?>
                    val w = VocalWindow()
                    w.startSec = ForkV2Json.num(x, "startSec")
                    w.fps = ForkV2Json.num(x, "fps")
                    w.curve = ForkV2Json.floats(x["curve"])
                    a.vocals.add(w)
                }
            }
            a.musicVideo = m["musicVideo"] == true
            a.subBassRatio = ForkV2Json.num(m, "subBassRatio")
            a.bassCurve = curveOf(m["bassCurve"])
            a.loudnessCurve = curveOf(m["loudnessCurve"])
            val chroma = ForkV2Json.nums(m["chroma"])
            a.chroma = if (chroma != null && chroma.size == 12) chroma else null
            a.chromaCurve = chromaCurveOf(m["chromaCurve"])
            a.analysisVersion = m["analysisVersion"] as String?
            return a
        }

        /** An analysis from its saved JSON text. */
        @Suppress("UNCHECKED_CAST")
        fun fromJsonText(text: String): ForkV2Analysis = fromJson(ForkV2Json.parse(text) as Map<String, Any?>)

        @Suppress("UNCHECKED_CAST")
        private fun sideOf(o: Any?): BeatSide? {
            if (o !is Map<*, *>) return null
            val m = o as Map<String, Any?>
            val s = BeatSide()
            s.beats = ForkV2Json.nums(m["beats"]) ?: DoubleArray(0)
            s.downbeats = ForkV2Json.nums(m["downbeats"]) ?: DoubleArray(0)
            s.bpm = ForkV2Json.num(m, "bpm")
            s.confidence = zero(ForkV2Json.num(m, "confidence"))
            return s
        }

        private fun zero(d: Double): Double = if (d.isNaN()) 0.0 else d
    }
}
