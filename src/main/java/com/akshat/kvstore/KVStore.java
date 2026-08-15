package com.akshat.kvstore;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.zip.CRC32;

/**
 * A persistent key-value store. Ugly on purpose, for now.
 *
 * Everything lives in this one class today. We will pull classes OUT of it
 * next weekend, once there is working code to point at.
 */
public class KVStore implements AutoCloseable {

    /**
     * Where a key's bytes live. This is the keydir entry - a pointer, not a record.
     * Package-private so tests in the same package can inspect it; there is no
     * public read path yet.
     */
    record Location(int fileId, long offset, int size) { }

    /** The keydir: key -> where to find it. Lives in RAM, dies on restart. */
    final Map<String, Location> keydir = new HashMap<>();

    private final FileChannel channel;
    private int fileId = 0;   // rollover is a later problem

    public KVStore(Path dataFile) throws IOException {
        RandomAccessFile raf = new RandomAccessFile(dataFile.toFile(), "rw");
        this.channel = raf.getChannel();
        this.channel.position(this.channel.size());   // always append at the end
    }

    public void put(String key, byte[] value) throws IOException {

        // ---------------------------------------------------------------
        // STEP 1 - build the payload: everything the CRC will cover.
        //
        // Layout (from RECORD-FORMAT.md), minus the CRC itself:
        //     timestamp (8) | keySize (4) | valSize (4) | key | value
        //
        // ByteBuffer is just a byte[] with a cursor. Each put* call writes
        // at the cursor and advances it, so the order of these calls IS the
        // order of the bytes on disk. Get the order wrong and the file is
        // unreadable - this is the part to be careful about.
        // ---------------------------------------------------------------
        byte[] keyBytes = key.getBytes(StandardCharsets.UTF_8);
        long timestamp = System.currentTimeMillis();

        ByteBuffer payload = ByteBuffer.allocate(8 + 4 + 4 + keyBytes.length + value.length);
        payload.putLong(timestamp);
        payload.putInt(keyBytes.length);
        payload.putInt(value.length);
        payload.put(keyBytes);
        payload.put(value);


        // ---------------------------------------------------------------
        // STEP 2 - compute the CRC over the payload bytes.        <- YOU
        //
        // Use java.util.zip.CRC32:  new CRC32(), .update(bytes), .getValue()
        // getValue() returns a long, but CRC32 is only 4 bytes - so you will
        // need to narrow it when you write it. Think about what you lose.
        // ---------------------------------------------------------------

        CRC32 crc = new CRC32();
        crc.update(payload.array());
        int checksum = (int) crc.getValue();

        // ---------------------------------------------------------------
        // STEP 3 - remember WHERE this record is about to land, then write. <- YOU
        //
        // Order matters and it is easy to get backwards: you need the offset
        // BEFORE you write, because writing moves the cursor. channel.position()
        // tells you where you are.
        //
        // Then write CRC first, payload second - one buffer, one write() call.
        // A ByteBuffer you have filled needs .flip() before it can be read
        // back out. Forgetting flip() writes zero bytes and no error.
        // ---------------------------------------------------------------
        // Where this record is about to land. Read it BEFORE writing,
        // because write() moves the cursor. long, not int - files get big.
        long offset = channel.position();

        // A second buffer, big enough for the CRC *plus* the payload.
        // The payload buffer has no room for the CRC - it was sized without it.
        ByteBuffer out = ByteBuffer.allocate(4 + payload.capacity());
        out.putInt(checksum);          // putInt writes all 4 bytes
        out.put(payload.array());      // then the payload, unchanged
        out.flip();                    // switch from write-mode to read-mode
        channel.write(out);            // channel READS from the buffer

        int recordSize = 4 + payload.capacity();

        // ---------------------------------------------------------------
        // STEP 4 - update the keydir so a later get() can find this.  <- YOU
        //
        // What are the three things a Location needs, and where does each
        // one come from?
        // ---------------------------------------------------------------
        keydir.put(key, new Location(fileId, offset, recordSize));
    }

    public byte[] get(String key) throws IOException {
        Location location = keydir.get(key);
        if (Objects.nonNull(location)) {
            ByteBuffer buffer = ByteBuffer.allocate(location.size);
            channel.read(buffer, location.offset());
            buffer.flip();
            int storedCrc = buffer.getInt();
            long ts = buffer.getLong();
            int keySize = buffer.getInt();
            int valSize = buffer.getInt();
            byte[] keyBytes = new byte[keySize];
            buffer.get(keyBytes);
            byte[] value = new byte[valSize];
            buffer.get(value);

            CRC32 crc = new CRC32();
            crc.update(buffer.array(), 4, location.size() - 4);
            int recomputedCrc = (int) crc.getValue();
            if (storedCrc != recomputedCrc) {
                throw  new IOException("Wrong checksum");
            }
            return value;
        }
        else {
            return null;
        }
    }

    @Override
    public void close() throws IOException {
        channel.close();
    }
}
