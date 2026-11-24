package com.clonsapps.engine.zip;

import com.clonsapps.engine.CancelToken;
import com.clonsapps.engine.CloneException;
import com.clonsapps.engine.ErrorCode;
import com.clonsapps.engine.util.ByteSink;

import java.io.Closeable;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

/**
 * Penulis ZIP yang melakukan alignment saat menulis (seperti zipalign), SEBELUM penandatanganan:
 * entri STORED disejajarkan 4 byte, library native (.so) STORED disejajarkan 16 KiB
 * (memenuhi perangkat halaman 4 KiB maupun 16 KiB). Padding ditulis sebagai extra field 0xD935.
 * Data entri yang tidak diubah disalin mentah, sambil diverifikasi CRC-32-nya.
 */
public final class AlignedZipWriter implements Closeable {
    public static final int DEFAULT_ALIGNMENT = 4;
    public static final int NATIVE_LIB_ALIGNMENT = 16384;
    private static final int ALIGN_EXTRA_ID = 0xD935;

    public interface Progress {
        void advance(long bytes);
    }

    private static final class Written {
        byte[] rawName;
        int flags;
        int method;
        int time;
        int date;
        long crc;
        long csize;
        long usize;
        long offset;
        int extAttrs;
        int madeBy;
    }

    private final OutputStream out;
    private long pos;
    private final List<Written> written = new ArrayList<>();
    private final byte[] copyBuf = new byte[256 * 1024];
    private boolean finished;

    public AlignedZipWriter(OutputStream out) {
        this.out = out;
    }

    public static int alignmentFor(String name, int method) {
        if (method != ZipArchive.METHOD_STORED) {
            return 1;
        }
        return name.endsWith(".so") ? NATIVE_LIB_ALIGNMENT : DEFAULT_ALIGNMENT;
    }

    private void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        pos += len;
    }

    private void writeLocalHeader(Written w, int alignment) throws IOException {
        int nameLen = w.rawName.length;
        long dataStartNoPad = pos + 30 + nameLen;
        int pad = 0;
        if (alignment > 1) {
            pad = (int) ((alignment - (dataStartNoPad % alignment)) % alignment);
            while (pad > 0 && pad < 6) {
                pad += alignment;
            }
        }
        ByteSink h = new ByteSink(30 + nameLen + pad);
        h.i32(0x04034b50);
        h.u16(w.method == ZipArchive.METHOD_STORED ? 10 : 20);
        h.u16(w.flags);
        h.u16(w.method);
        h.u16(w.time);
        h.u16(w.date);
        h.i32((int) w.crc);
        h.i32((int) w.csize);
        h.i32((int) w.usize);
        h.u16(nameLen);
        h.u16(pad);
        h.bytes(w.rawName);
        if (pad > 0) {
            h.u16(ALIGN_EXTRA_ID);
            h.u16(pad - 4);
            h.u16(alignment > 0xFFFF ? 0xFFFF : alignment);
            h.zeros(pad - 6);
        }
        byte[] hb = h.toByteArray();
        w.offset = pos;
        write(hb, 0, hb.length);
    }

    /**
     * Menyalin entri apa adanya dari arsip sumber (tanpa kompresi ulang) dengan alignment baru,
     * sambil memverifikasi CRC-32 isi yang sebenarnya.
     */
    public void copyRaw(ZipArchive src, ZipArchive.Entry e, CancelToken cancel, Progress progress) throws CloneException {
        Written w = new Written();
        w.rawName = e.rawName;
        w.flags = e.flags & ~0x0008; // ukuran & CRC ditulis di local header, tanpa data descriptor
        w.method = e.method;
        w.time = e.dosTime;
        w.date = e.dosDate;
        w.crc = e.crc;
        w.csize = e.compressedSize;
        w.usize = e.size;
        w.extAttrs = e.externalAttrs;
        w.madeBy = e.versionMadeBy;
        Inflater inf = e.method == ZipArchive.METHOD_DEFLATED ? new Inflater(true) : null;
        byte[] inflateBuf = inf != null ? new byte[256 * 1024] : null;
        CRC32 crc = new CRC32();
        long produced = 0;
        try {
            writeLocalHeader(w, alignmentFor(e.name, e.method));
            long done = 0;
            while (done < e.compressedSize) {
                CancelToken.check(cancel);
                int n = src.readRaw(e, done, copyBuf, 0, copyBuf.length);
                if (n <= 0) {
                    break;
                }
                write(copyBuf, 0, n);
                if (inf == null) {
                    crc.update(copyBuf, 0, n);
                    produced += n;
                } else {
                    inf.setInput(copyBuf, 0, n);
                    while (true) {
                        int m = inf.inflate(inflateBuf);
                        if (m > 0) {
                            crc.update(inflateBuf, 0, m);
                            produced += m;
                        } else {
                            break;
                        }
                    }
                }
                done += n;
                if (progress != null) {
                    progress.advance(n);
                }
            }
            if (inf != null && !inf.finished()) {
                throw new CloneException(ErrorCode.CRC_MISMATCH, e.name + ": aliran deflate tidak lengkap");
            }
            if (produced != e.size || crc.getValue() != e.crc) {
                throw new CloneException(ErrorCode.CRC_MISMATCH, e.name);
            }
            written.add(w);
        } catch (DataFormatException ex) {
            throw new CloneException(ErrorCode.CRC_MISMATCH, e.name + ": data deflate rusak", ex);
        } catch (IOException ex) {
            throw ioFailure(e.name, ex);
        } finally {
            if (inf != null) {
                inf.end();
            }
        }
    }

    /** Menulis entri baru/diubah. {@code compress=false} menyimpan STORED dan diberi alignment. */
    public void addBytes(String name, byte[] data, boolean compress, int dosTime, int dosDate) throws CloneException {
        Written w = new Written();
        w.rawName = name.getBytes(StandardCharsets.UTF_8);
        boolean ascii = true;
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) > 0x7F) {
                ascii = false;
                break;
            }
        }
        w.flags = ascii ? 0 : 0x0800;
        w.time = dosTime;
        w.date = dosDate;
        CRC32 crc = new CRC32();
        crc.update(data, 0, data.length);
        w.crc = crc.getValue();
        w.usize = data.length;
        byte[] payload = data;
        if (compress) {
            Deflater d = new Deflater(Deflater.BEST_COMPRESSION, true);
            try {
                d.setInput(data);
                d.finish();
                ByteSink bs = new ByteSink(Math.max(64, data.length / 2));
                byte[] chunk = new byte[64 * 1024];
                while (!d.finished()) {
                    int n = d.deflate(chunk);
                    bs.bytes(chunk, 0, n);
                }
                payload = bs.toByteArray();
            } finally {
                d.end();
            }
            w.method = ZipArchive.METHOD_DEFLATED;
        } else {
            w.method = ZipArchive.METHOD_STORED;
        }
        w.csize = payload.length;
        w.madeBy = 20;
        w.extAttrs = 0;
        try {
            writeLocalHeader(w, alignmentFor(name, w.method));
            write(payload, 0, payload.length);
            written.add(w);
        } catch (IOException ex) {
            throw ioFailure(name, ex);
        }
    }

    public void finish() throws CloneException {
        if (finished) {
            return;
        }
        try {
            long cdStart = pos;
            for (Written w : written) {
                ByteSink c = new ByteSink(46 + w.rawName.length);
                c.i32(0x02014b50);
                c.u16(w.madeBy == 0 ? 20 : w.madeBy);
                c.u16(w.method == ZipArchive.METHOD_STORED ? 10 : 20);
                c.u16(w.flags);
                c.u16(w.method);
                c.u16(w.time);
                c.u16(w.date);
                c.i32((int) w.crc);
                c.i32((int) w.csize);
                c.i32((int) w.usize);
                c.u16(w.rawName.length);
                c.u16(0);
                c.u16(0);
                c.u16(0);
                c.u16(0);
                c.i32(w.extAttrs);
                c.i32((int) w.offset);
                c.bytes(w.rawName);
                byte[] cb = c.toByteArray();
                write(cb, 0, cb.length);
            }
            long cdSize = pos - cdStart;
            if (written.size() > 0xFFFF || pos > 0xFFFFFFFFL) {
                throw new CloneException(ErrorCode.ZIP64_UNSUPPORTED, "hasil melebihi batas ZIP32");
            }
            ByteSink e = new ByteSink(22);
            e.i32(0x06054b50);
            e.u16(0);
            e.u16(0);
            e.u16(written.size());
            e.u16(written.size());
            e.i32((int) cdSize);
            e.i32((int) cdStart);
            e.u16(0);
            byte[] eb = e.toByteArray();
            write(eb, 0, eb.length);
            out.flush();
            finished = true;
        } catch (IOException ex) {
            throw ioFailure("central directory", ex);
        }
    }

    static CloneException ioFailure(String what, IOException ex) {
        String msg = String.valueOf(ex.getMessage());
        if (msg.contains("ENOSPC") || msg.toLowerCase().contains("no space left")) {
            return new CloneException(ErrorCode.STORAGE_FULL, "gagal menulis " + what, ex);
        }
        return new CloneException(ErrorCode.IO_ERROR, what + ": " + msg, ex);
    }

    @Override
    public void close() throws IOException {
        out.close();
    }
}
