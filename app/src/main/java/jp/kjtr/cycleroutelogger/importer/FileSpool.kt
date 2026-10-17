package jp.kjtr.cycleroutelogger.importer

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 点の一時ファイル（1 点 32 バイト: 時刻 8・緯度 8・経度 8・高度 4・速度 4）。数十万〜数百万点でもメモリに持たない。
 * 取り込みが終わる・やめると [close] で消す（[deleteOnClose]）。
 */
class FileSpool(private val file: File, private val deleteOnClose: Boolean = true) : PointStore {
    private val raf = RandomAccessFile(file, "rw").also { it.setLength(0) }
    private val ch = raf.channel
    private val buf = ByteBuffer.allocate(RECORD * 2048).order(ByteOrder.LITTLE_ENDIAN)
    private var written = 0L
    private var count = 0L

    override val size: Long get() = count

    override fun append(timeMs: Long, lat: Double, lon: Double, ele: Double?, speedMps: Float?) {
        if (buf.remaining() < RECORD) flush()
        buf.putLong(timeMs).putDouble(lat).putDouble(lon).putFloat(ele?.toFloat() ?: Float.NaN).putFloat(speedMps ?: Float.NaN)
        count++
    }

    private fun flush() {
        buf.flip()
        while (buf.hasRemaining()) written += ch.write(buf, written)
        buf.clear()
    }

    override fun read(first: Long, count: Int): PointBlock {
        require(first >= 0 && first + count <= this.count)
        if (buf.position() > 0) flush()
        val t = LongArray(count)
        val la = DoubleArray(count)
        val lo = DoubleArray(count)
        val e = FloatArray(count)
        val s = FloatArray(count)
        val chunk = ByteBuffer.allocate(RECORD * 4096).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        var pos = first * RECORD
        while (i < count) {
            val n = minOf(4096, count - i)
            chunk.clear()
            chunk.limit(n * RECORD)
            while (chunk.hasRemaining()) {
                val r = ch.read(chunk, pos + chunk.position())
                if (r < 0) error("spool truncated")
            }
            chunk.flip()
            repeat(n) {
                t[i] = chunk.long; la[i] = chunk.double; lo[i] = chunk.double; e[i] = chunk.float; s[i] = chunk.float
                i++
            }
            pos += n.toLong() * RECORD
        }
        return PointBlock(t, la, lo, e, s)
    }

    override fun close() {
        runCatching { ch.close() }
        runCatching { raf.close() }
        if (deleteOnClose) file.delete()
    }

    companion object {
        const val RECORD = 32
    }
}
