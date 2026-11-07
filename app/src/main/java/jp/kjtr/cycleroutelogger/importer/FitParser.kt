package jp.kjtr.cycleroutelogger.importer

/**
 * FIT（Garmin などの活動ファイル。バイナリ）の最小の読み取り（自前実装。Garmin FIT SDK は独自ライセンスなので使わない）。
 *
 * - ヘッダ（12 / 14 バイト、".FIT"）→ 記録（定義メッセージ・データメッセージ・圧縮タイムスタンプのヘッダ）→ ファイルの CRC。
 *   ヘッダの後にもう 1 つ FIT が続く（連結された）ファイルも読む。
 * - 定義メッセージ: アーキテクチャ（リトル / ビッグエンディアン）・グローバル番号・フィールド（番号・大きさ・基本型）、
 *   開発者フィールド（ヘッダの 0x20）は大きさだけ足して読み飛ばす。
 * - 使うもの: record（グローバル 20）の timestamp(253)・position_lat/long(0/1、semicircles)・altitude(2) / enhanced_altitude(78)・
 *   speed(6) / enhanced_speed(73)。session(18) / sport(12) の sport(5)・sub_sport(6)（58 = virtual_activity はバーチャル）。心拍・ケイデンスは使わない（保存する列が無い）。
 *   時刻は 1989-12-31T00:00:00Z からの秒。どのメッセージの timestamp(253) も圧縮タイムスタンプの基準になる。
 * - 位置の無い record（屋内・センサーだけ）は数えるだけで点にしない。
 * - CRC（FIT の CRC-16）は確かめるが、合わなくても読めた点は使う（[Result.crcOk] = false。電池切れで途中まで書かれたファイルが多いため）。
 *   途中で切れていれば読めたところまで（[Result.truncated]）。
 */
object FitParser {
    /** FIT の時刻の起点（1989-12-31T00:00:00Z）の UNIX 秒。 */
    const val FIT_EPOCH_S = 631_065_600L
    private const val SEMI_TO_DEG = 180.0 / 2147483648.0

    const val MESG_RECORD = 20
    const val MESG_FILE_ID = 0
    const val MESG_SESSION = 18
    const val MESG_SPORT = 12

    class NotFitException : Exception("not a FIT file")

    data class Result(
        /** 位置つきで [PointSink] に渡した record の数。 */
        val points: Int,
        /** 位置の無い record の数。 */
        val noPosition: Int,
        val crcOk: Boolean,
        val truncated: Boolean,
        val sport: Sport,
        /** file_id（グローバル 0）の 製造元/シリアル/作成時刻（作成時刻があるとき。取り込みの印。0.10.1）。 */
        val fileId: String? = null,
    )

    private class Def(val global: Int, val big: Boolean, val nums: IntArray, val sizes: IntArray, val size: Int)

    /** [bytes] が FIT のヘッダで始まっているか。 */
    fun looksLikeFit(bytes: ByteArray): Boolean =
        bytes.size >= 12 && (bytes[0].toInt() and 0xFF) >= 12 && bytes[8] == '.'.code.toByte() && bytes[9] == 'F'.code.toByte() &&
            bytes[10] == 'I'.code.toByte() && bytes[11] == 'T'.code.toByte()

    fun parse(bytes: ByteArray, sink: PointSink, check: () -> Unit = {}): Result {
        if (!looksLikeFit(bytes)) throw NotFitException()
        val st = State(bytes, sink, check)
        var pos = 0
        var files = 0
        while (pos + 12 <= bytes.size) {
            val hsize = u8(bytes, pos)
            if (hsize < 12 || pos + hsize > bytes.size || !looksLikeFitAt(bytes, pos)) {
                if (files == 0) throw NotFitException()
                break
            }
            val dataSize = u32(bytes, pos + 4, false)
            val dataStart = pos + hsize
            var dataEnd = dataStart + dataSize
            var complete = true
            if (dataSize <= 0L || dataEnd + 2 > bytes.size) {
                // 途中で切れている（または大きさが書かれていない）。最後まで読む
                complete = false
                dataEnd = bytes.size.toLong()
            }
            val end = dataEnd.toInt()
            if (complete) {
                val want = u16(bytes, end, false)
                if (crc(bytes, pos, end) != want) st.crcOk = false
            } else {
                st.truncated = true
            }
            st.records(dataStart, end)
            files++
            if (!complete) break
            pos = end + 2
        }
        return Result(st.points, st.noPosition, st.crcOk, st.truncated, st.sport, st.fileId)
    }

    private fun looksLikeFitAt(b: ByteArray, p: Int) =
        b[p + 8] == '.'.code.toByte() && b[p + 9] == 'F'.code.toByte() && b[p + 10] == 'I'.code.toByte() && b[p + 11] == 'T'.code.toByte()

    private class State(val b: ByteArray, val sink: PointSink, val check: () -> Unit) {
        var points = 0
        var noPosition = 0
        var crcOk = true
        var truncated = false
        var sport = Sport.UNKNOWN
        var fileId: String? = null
        var lastTs = -1L

        fun records(start: Int, end: Int) {
            val defs = arrayOfNulls<Def>(16)
            var p = start
            lastTs = -1L
            sink.beginRun()
            while (p < end) {
                val h = u8(b, p)
                p++
                if (h and 0x80 != 0) {
                    // 圧縮タイムスタンプのヘッダ: ローカル番号 2 ビット・時刻の下 5 ビット
                    val def = defs[(h shr 5) and 0x03] ?: run { truncated = true; return }
                    if (p + def.size > end) { truncated = true; return }
                    var ts = -1L
                    if (lastTs >= 0) {
                        val off = (h and 0x1F).toLong()
                        ts = (lastTs and 0x1F.toLong().inv()) + off
                        if (off < (lastTs and 0x1F)) ts += 0x20
                        lastTs = ts
                    }
                    data(def, p, ts)
                    p += def.size
                } else if (h and 0x40 != 0) {
                    // 定義メッセージ
                    if (p + 5 > end) { truncated = true; return }
                    val big = u8(b, p + 1) == 1
                    val global = u16(b, p + 2, big)
                    val n = u8(b, p + 4)
                    p += 5
                    if (p + n * 3 > end) { truncated = true; return }
                    val nums = IntArray(n)
                    val sizes = IntArray(n)
                    var size = 0
                    for (i in 0 until n) {
                        nums[i] = u8(b, p)
                        sizes[i] = u8(b, p + 1)
                        size += sizes[i]
                        p += 3
                    }
                    if (h and 0x20 != 0) {
                        // 開発者フィールド: 大きさだけ足して読み飛ばす
                        if (p + 1 > end) { truncated = true; return }
                        val nd = u8(b, p)
                        p++
                        if (p + nd * 3 > end) { truncated = true; return }
                        for (i in 0 until nd) {
                            size += u8(b, p + 1)
                            p += 3
                        }
                    }
                    defs[h and 0x0F] = Def(global, big, nums, sizes, size)
                } else {
                    val def = defs[h and 0x0F] ?: run { truncated = true; return }
                    if (p + def.size > end) { truncated = true; return }
                    data(def, p, -1L)
                    p += def.size
                }
            }
        }

        /** データメッセージ 1 つ。[compressedTs] は圧縮タイムスタンプのヘッダから（無ければ -1）。 */
        fun data(def: Def, start: Int, compressedTs: Long) {
            var ts = compressedTs
            var lat = INVALID_I32
            var lon = INVALID_I32
            var alt = -1L
            var altEnh = -1L
            var spd = -1L
            var spdEnh = -1L
            var sportCode = -1
            var subSport = -1
            var manufacturer = -1L
            var serial = -1L
            var created = -1L
            var off = start
            for (i in def.nums.indices) {
                val num = def.nums[i]
                val sz = def.sizes[i]
                when {
                    num == 253 && sz == 4 -> u32(b, off, def.big).takeIf { it != 0xFFFFFFFFL }?.let { ts = it; lastTs = it }
                    def.global == MESG_RECORD -> when {
                        num == 0 && sz == 4 -> lat = s32(b, off, def.big)
                        num == 1 && sz == 4 -> lon = s32(b, off, def.big)
                        num == 2 && sz == 2 -> alt = u16(b, off, def.big).toLong().takeIf { it != 0xFFFFL } ?: -1L
                        num == 78 && sz == 4 -> altEnh = u32(b, off, def.big).takeIf { it != 0xFFFFFFFFL } ?: -1L
                        num == 6 && sz == 2 -> spd = u16(b, off, def.big).toLong().takeIf { it != 0xFFFFL } ?: -1L
                        num == 73 && sz == 4 -> spdEnh = u32(b, off, def.big).takeIf { it != 0xFFFFFFFFL } ?: -1L
                    }
                    (def.global == MESG_SESSION || def.global == MESG_SPORT) && num == 5 && sz == 1 -> sportCode = u8(b, off)
                    (def.global == MESG_SESSION || def.global == MESG_SPORT) && num == 6 && sz == 1 -> subSport = u8(b, off)
                    def.global == MESG_FILE_ID && num == 1 && sz == 2 -> manufacturer = u16(b, off, def.big).toLong()
                    def.global == MESG_FILE_ID && num == 3 && sz == 4 -> serial = u32(b, off, def.big)
                    def.global == MESG_FILE_ID && num == 4 && sz == 4 -> created = u32(b, off, def.big).takeIf { it != 0xFFFFFFFFL && it != 0L } ?: -1L
                }
                off += sz
            }
            if (sportCode >= 0) {
                val s = Sport.ofFit(sportCode, subSport)
                // session が複数（マルチスポーツ）なら自転車を優先
                if (s == Sport.CYCLING || sport == Sport.UNKNOWN) sport = s
            }
            if (def.global == MESG_FILE_ID && created >= 0 && fileId == null) fileId = "$manufacturer/$serial/$created"
            if (def.global != MESG_RECORD) return
            if (ts < 0) return
            if (lat == INVALID_I32 || lon == INVALID_I32) {
                noPosition++
                return
            }
            val ele = when {
                altEnh >= 0 -> altEnh / 5.0 - 500.0
                alt >= 0 -> alt / 5.0 - 500.0
                else -> null
            }
            val speed = when {
                spdEnh >= 0 -> (spdEnh / 1000.0).toFloat()
                spd >= 0 -> (spd / 1000.0).toFloat()
                else -> null
            }
            sink.point((ts + FIT_EPOCH_S) * 1000L, lat * SEMI_TO_DEG, lon * SEMI_TO_DEG, ele, speed)
            points++
            if (points % 4096 == 0) check()
        }
    }

    private const val INVALID_I32 = 0x7FFFFFFF

    private fun u8(b: ByteArray, p: Int) = b[p].toInt() and 0xFF

    private fun u16(b: ByteArray, p: Int, big: Boolean): Int =
        if (big) (u8(b, p) shl 8) or u8(b, p + 1) else u8(b, p) or (u8(b, p + 1) shl 8)

    private fun u32(b: ByteArray, p: Int, big: Boolean): Long =
        if (big) {
            (u8(b, p).toLong() shl 24) or (u8(b, p + 1).toLong() shl 16) or (u8(b, p + 2).toLong() shl 8) or u8(b, p + 3).toLong()
        } else {
            u8(b, p).toLong() or (u8(b, p + 1).toLong() shl 8) or (u8(b, p + 2).toLong() shl 16) or (u8(b, p + 3).toLong() shl 24)
        }

    private fun s32(b: ByteArray, p: Int, big: Boolean): Int = u32(b, p, big).toInt()

    private val CRC_TABLE = intArrayOf(
        0x0000, 0xCC01, 0xD801, 0x1400, 0xF001, 0x3C00, 0x2800, 0xE401,
        0xA001, 0x6C00, 0x7800, 0xB401, 0x5000, 0x9C01, 0x8801, 0x4400,
    )

    /** FIT の CRC-16（[from] から [to] の手前まで）。 */
    fun crc(b: ByteArray, from: Int, to: Int): Int {
        var crc = 0
        for (i in from until to) {
            val byte = b[i].toInt() and 0xFF
            var tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[byte and 0xF]
            tmp = CRC_TABLE[crc and 0xF]
            crc = (crc shr 4) and 0x0FFF
            crc = crc xor tmp xor CRC_TABLE[(byte shr 4) and 0xF]
        }
        return crc
    }
}
