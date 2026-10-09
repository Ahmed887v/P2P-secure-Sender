package com.p2psecure.share

import android.os.ParcelFileDescriptor
import android.system.Os
import java.security.MessageDigest

class FileSource(
    private val pfd: ParcelFileDescriptor,
    val size: Long,
    val name: String
) {
    fun read(index: Int, chunkSize: Int): ByteArray {
        val offset = index.toLong() * chunkSize
        if (offset >= size) return ByteArray(0)
        val len = minOf(chunkSize.toLong(), size - offset).toInt()
        val buf = ByteArray(len)
        var got = 0
        while (got < len) {
            val n = Os.pread(pfd.fileDescriptor, buf, got, len - got, offset + got)
            if (n <= 0) break
            got += n
        }
        return if (got == len) buf else buf.copyOf(got)
    }

    fun sha256(chunkSize: Int): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(chunkSize)
        var off = 0L
        while (off < size) {
            val len = minOf(buf.size.toLong(), size - off).toInt()
            var got = 0
            while (got < len) {
                val n = Os.pread(pfd.fileDescriptor, buf, got, len - got, off + got)
                if (n <= 0) break
                got += n
            }
            if (got <= 0) break
            md.update(buf, 0, got)
            off += got
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    fun close() {
        try {
            pfd.close()
        } catch (_: Exception) {
        }
    }
}
