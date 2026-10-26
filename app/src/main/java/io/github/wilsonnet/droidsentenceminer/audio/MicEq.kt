package io.github.wilsonnet.droidsentenceminer.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A speaker → microphone correction curve, measured on this device by [MicCalibration]: gains of a
 * fixed bank of third-octave peaking filters ([MicEq.CENTERS]). [measuredDb] keeps the measured
 * response (sixth-octave points from [MicEq.PLOT_FROM_HZ]) for the settings graph.
 */
@Serializable
data class MicEqProfile(
    val gainsDb: List<Double>,
    val measuredDb: List<Double> = emptyList(),
    val model: String = "",
    val createdAtMs: Long = 0,
) {
    fun encode(): String = Json.encodeToString(serializer(), this)

    companion object {
        fun decode(raw: String?): MicEqProfile? =
            raw?.takeIf { it.isNotBlank() }?.let { runCatching { Json.decodeFromString(serializer(), it) }.getOrNull() }
    }
}

/**
 * The microphone fallback hears game audio through the device's own speaker, which has
 * almost no bass, a harsh 4–8 kHz peak and dips of its own (measured on Xiaomi Pad 7: −12 dB at
 * 160 Hz, −13 dB at 2.5 kHz, +16 dB at 6.3 kHz against 1 kHz). Pink noise played through the
 * speaker and recorded back gives that curve ([response]); [fit] turns it into gains for a bank
 * of peaking filters that undo it. Boosts are capped lower than cuts: pushing up bands where the
 * speaker has nothing left mostly lifts room noise and reverb (scored with PESQ on recorded
 * speech: +6 dB caps beat +9/+12).
 *
 * Pure Kotlin, unit tested.
 */
object MicEq {
    /** Third-octave peaking bank, 160 Hz – 10 kHz. */
    val CENTERS: DoubleArray = DoubleArray(19) { 160.0 * 2.0.pow(it / 3.0) }
    const val Q = 3.0
    const val HIGH_PASS_HZ = 120.0
    const val MAX_BOOST_DB = 6.0
    const val MAX_CUT_DB = 20.0

    /** Below this the speaker plays nothing usable: the correction fades to 0 dB an octave under it. */
    private const val LOW_LIMIT_HZ = 170.0
    private const val RIDGE = 0.5
    private const val FIT_ROUNDS = 4

    const val PLOT_FROM_HZ = 60.0
    private const val GRID_STEPS_PER_OCTAVE = 24
    private const val SEGMENT = 4096

    /** 1/24-octave analysis grid from [PLOT_FROM_HZ] up to 11 kHz (or 0.46 × sample rate). */
    fun grid(sampleRate: Int): DoubleArray {
        val top = minOf(11_000.0, sampleRate * 0.46)
        val steps = (ln(top / PLOT_FROM_HZ) / ln(2.0) * GRID_STEPS_PER_OCTAVE).toInt()
        return DoubleArray(steps + 1) { PLOT_FROM_HZ * 2.0.pow(it.toDouble() / GRID_STEPS_PER_OCTAVE) }
    }

    fun centers(sampleRate: Int): DoubleArray = CENTERS.filter { it < sampleRate * 0.45 }.toDoubleArray()

    /**
     * Speaker → mic response in dB on [grid], third-octave smoothed, 0 dB = median of 500 Hz – 2 kHz.
     * [played] and [recorded] need not be aligned (power spectra only); [noise] is the mic with
     * nothing playing and is subtracted from the recording. Returns null when the mid-band SNR is
     * under [minSnrDb] (volume too low, or something else playing).
     */
    fun response(
        played: DoubleArray,
        recorded: DoubleArray,
        noise: DoubleArray,
        sampleRate: Int,
        minSnrDb: Double = 15.0,
    ): DoubleArray? {
        val pxx = welch(played)
        val pyy = welch(recorded)
        val pnn = welch(noise)
        val binHz = sampleRate.toDouble() / SEGMENT
        fun band(p: DoubleArray, lo: Double, hi: Double): Double {
            val from = (lo / binHz).toInt().coerceIn(1, p.size - 1)
            val to = (hi / binHz).toInt().coerceIn(from, p.size - 1)
            var sum = 0.0
            for (k in from..to) sum += p[k]
            return sum / (to - from + 1)
        }
        val snr = 10 * log10(band(pyy, 500.0, 4000.0) / band(pnn, 500.0, 4000.0).coerceAtLeast(1e-30))
        if (snr < minSnrDb) return null
        val clean = DoubleArray(pyy.size) { maxOf(pyy[it] - pnn[it], pyy[it] * 0.1) }
        val g = grid(sampleRate)
        val third = 2.0.pow(1.0 / 6)
        val h = DoubleArray(g.size) { 10 * log10(band(clean, g[it] / third, g[it] * third) / band(pxx, g[it] / third, g[it] * third).coerceAtLeast(1e-30)) }
        val mid = g.indices.filter { g[it] in 500.0..2000.0 }.map { h[it] }.sorted()
        val ref = mid[mid.size / 2]
        return DoubleArray(h.size) { h[it] - ref }
    }

    /**
     * Least-squares gains for [centers] that bring [responseDb] (on [grid]) to flat, ridge-regularized
     * (also on neighbour differences, so adjacent bands don't fight), clamped, re-linearized a few
     * times since a peaking filter's dB curve is not exactly proportional to its gain.
     */
    fun fit(responseDb: DoubleArray, sampleRate: Int): DoubleArray {
        val g = grid(sampleRate)
        val centers = centers(sampleRate)
        val n = centers.size
        val target = DoubleArray(g.size) {
            val fade = (ln(g[it] / (LOW_LIMIT_HZ / 2)) / ln(2.0)).coerceIn(0.0, 1.0)
            (-responseDb[it]).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB) * fade
        }
        val basis = Array(n) { j ->
            val unit = Biquad.peaking(sampleRate, centers[j], 6.0, Q)
            DoubleArray(g.size) { unit.magnitudeDb(g[it], sampleRate) / 6.0 }
        }
        val a = Array(n) { i -> DoubleArray(n) { j -> (0 until g.size).sumOf { basis[i][it] * basis[j][it] } } }
        for (i in 0 until n) {
            a[i][i] += RIDGE
            // D^T D of the first-difference matrix: 1, 2, …, 2, 1 on the diagonal, -1 beside it.
            a[i][i] += RIDGE * (if (i == 0 || i == n - 1) 1 else 2)
            if (i > 0) a[i][i - 1] -= RIDGE
            if (i < n - 1) a[i][i + 1] -= RIDGE
        }
        val gains = DoubleArray(n)
        repeat(FIT_ROUNDS) {
            val current = curveDb(gains, sampleRate, g, highPass = false)
            val b = DoubleArray(n) { j -> (0 until g.size).sumOf { basis[j][it] * (target[it] - current[it]) } }
            val step = solve(a, b)
            for (j in 0 until n) gains[j] = (gains[j] + step[j]).coerceIn(-MAX_CUT_DB, MAX_BOOST_DB)
        }
        return gains
    }

    /** The correction's own response in dB on [freqs] (optionally with the high-pass). */
    fun curveDb(gainsDb: DoubleArray, sampleRate: Int, freqs: DoubleArray, highPass: Boolean = true): DoubleArray {
        val sections = sections(gainsDb, sampleRate, highPass)
        return DoubleArray(freqs.size) { i -> sections.sumOf { it.magnitudeDb(freqs[i], sampleRate) } }
    }

    /** The filter chain: high-pass, then every band that does anything. */
    fun sections(gainsDb: DoubleArray?, sampleRate: Int, highPass: Boolean = true): List<Biquad> = buildList {
        if (highPass) add(Biquad.highPass(sampleRate, HIGH_PASS_HZ))
        val centers = centers(sampleRate)
        gainsDb?.forEachIndexed { i, gain ->
            if (i < centers.size && kotlin.math.abs(gain) >= 0.1) add(Biquad.peaking(sampleRate, centers[i], gain, Q))
        }
    }

    /** Measured response resampled to sixth-octave points for storing in the profile. */
    fun plotPoints(responseDb: DoubleArray): List<Double> =
        responseDb.indices.filter { it % (GRID_STEPS_PER_OCTAVE / 6) == 0 }.map { (responseDb[it] * 10).toInt() / 10.0 }

    fun plotFreqs(count: Int): DoubleArray = DoubleArray(count) { PLOT_FROM_HZ * 2.0.pow(it / 6.0) }

    /** Welch power spectrum, Hann window, 50 % overlap, bins 0..SEGMENT/2. */
    fun welch(x: DoubleArray): DoubleArray {
        val half = SEGMENT / 2
        val out = DoubleArray(half + 1)
        val window = DoubleArray(SEGMENT) { 0.5 - 0.5 * cos(2 * PI * it / SEGMENT) }
        val re = DoubleArray(SEGMENT)
        val im = DoubleArray(SEGMENT)
        var segments = 0
        var start = 0
        while (start + SEGMENT <= x.size) {
            for (i in 0 until SEGMENT) {
                re[i] = x[start + i] * window[i]
                im[i] = 0.0
            }
            fft(re, im)
            for (k in 0..half) out[k] += re[k] * re[k] + im[k] * im[k]
            segments++
            start += half
        }
        if (segments > 0) for (k in out.indices) out[k] /= segments
        return out
    }

    /** In-place iterative radix-2 FFT; size must be a power of two. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= n) {
            val angle = -2 * PI / len
            val wr = cos(angle)
            val wi = sin(angle)
            var i = 0
            while (i < n) {
                var cr = 1.0
                var ci = 0.0
                for (k in 0 until len / 2) {
                    val ar = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val ai = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k + len / 2] = re[i + k] - ar
                    im[i + k + len / 2] = im[i + k] - ai
                    re[i + k] += ar
                    im[i + k] += ai
                    val next = cr * wr - ci * wi
                    ci = cr * wi + ci * wr
                    cr = next
                }
                i += len
            }
            len = len shl 1
        }
    }

    /** Gaussian elimination with partial pivoting (the system is small and well conditioned). */
    private fun solve(matrix: Array<DoubleArray>, rhs: DoubleArray): DoubleArray {
        val n = rhs.size
        val a = Array(n) { matrix[it].copyOf() }
        val b = rhs.copyOf()
        for (col in 0 until n) {
            val pivot = (col until n).maxBy { kotlin.math.abs(a[it][col]) }
            if (pivot != col) {
                val row = a[col]; a[col] = a[pivot]; a[pivot] = row
                val v = b[col]; b[col] = b[pivot]; b[pivot] = v
            }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                for (c in col until n) a[r][c] -= f * a[col][c]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (c in r + 1 until n) s -= a[r][c] * x[c]
            x[r] = s / a[r][r]
        }
        return x
    }

    /** Pink noise (Paul Kellet's filter over Gaussian white noise) at [rmsDbfs] RMS, in [-1, 1]. */
    fun pinkNoise(samples: Int, rmsDbfs: Double, seed: Long = 7): DoubleArray {
        val rnd = java.util.Random(seed)
        var b0 = 0.0; var b1 = 0.0; var b2 = 0.0; var b3 = 0.0; var b4 = 0.0; var b5 = 0.0; var b6 = 0.0
        val out = DoubleArray(samples) {
            val w = rnd.nextGaussian()
            b0 = 0.99886 * b0 + w * 0.0555179
            b1 = 0.99332 * b1 + w * 0.0750759
            b2 = 0.96900 * b2 + w * 0.1538520
            b3 = 0.86650 * b3 + w * 0.3104856
            b4 = 0.55000 * b4 + w * 0.5329522
            b5 = -0.7616 * b5 - w * 0.0168980
            val p = b0 + b1 + b2 + b3 + b4 + b5 + b6 + w * 0.5362
            b6 = w * 0.115926
            p
        }
        val rms = sqrt(out.sumOf { it * it } / samples.coerceAtLeast(1)).coerceAtLeast(1e-12)
        val scale = 10.0.pow(rmsDbfs / 20) / rms
        for (i in out.indices) out[i] = (out[i] * scale).coerceIn(-1.0, 1.0)
        return out
    }
}
