package com.akshat.kvstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.*;

class KVStoreTest {

    /**
     * JUnit creates a fresh empty directory per test and deletes it after.
     */
    @TempDir
    Path tmp;

    @Test
    void putAppendsRecordsAndIndexesThem() throws IOException {
        Path dataFile = tmp.resolve("data.db");

        KVStore store = new KVStore(dataFile);
        store.put("user:1", "ann".getBytes());
        store.put("user:2", "bob".getBytes());
        store.put("user:1", "amy".getBytes());

        store.keydir.forEach((k, v) -> System.out.println(k + "  ->  " + v));

        KVStore.Location latest = store.keydir.get("user:1");
        store.close();

        // One record = 4 CRC + 8 ts + 4 keySize + 4 valSize + 6 key + 3 value = 29 bytes.
        // Three puts, nothing ever overwritten, so all three are on disk.
        assertEquals(87, Files.size(dataFile), "three 29-byte records should be appended");

        // Three writes, two distinct keys - the index holds one entry per live key.
        assertEquals(2, store.keydir.size(), "keydir holds live keys, not records");

        // The second write to user:1 lands after two earlier records.
        assertEquals(58, latest.offset(), "keydir must point at the NEWEST copy");
        assertEquals(29, latest.size());

        // Only one data file exists so far. This assertion is here specifically
        // because the compiler cannot catch a wrong int passed positionally.
        assertEquals(0, latest.fileId(), "there is only one data file yet");
    }

    @Test
    void getTest() throws IOException {
        Path dataFile = tmp.resolve("data.db");
        KVStore store = new KVStore(dataFile);
        store.put("user:1", "ann".getBytes());
        store.put("user:2", "bob".getBytes());
        store.put("user:1", "amy".getBytes());
        assertEquals("amy", new String(store.get("user:1")));
    }

    @Test
    void putWithOverwrite()
        throws Exception {
        Path dataFile = tmp.resolve("data.db");
        KVStore store = new KVStore(dataFile);
        store.put("user:1", "ann".getBytes());
        store.put("user:2", "robert".getBytes());
        store.keydir.put("user:1", new KVStore.Location(0, 29, 32));
        assertThrows(IllegalStateException.class, () -> store.get("user:1"));
    }

    @Test
    void testRestore() throws IOException {
        Path dataFile = tmp.resolve("data.db");
        KVStore store = new KVStore(dataFile);
        store.put("user:1", "ann".getBytes());
        store.put("user:2", "bob".getBytes());
        store.put("user:1", "amy".getBytes());
        store.keydir.clear();
        store.close();
        store = new KVStore(dataFile);
        assertEquals("amy", new String(store.get("user:1"), StandardCharsets.UTF_8));
    }

    @Test
    void restoreTruncatesCorruptTail() throws IOException {
        Path dataFile = tmp.resolve("data.db");
        try (KVStore store = new KVStore(dataFile)) {
            store.put("user:1", "ann".getBytes(StandardCharsets.UTF_8));
            store.put("user:2", "bob".getBytes(StandardCharsets.UTF_8));
            store.put("user:1", "amy".getBytes(StandardCharsets.UTF_8));
        }
        long goodSize = Files.size(dataFile);
        Files.write(dataFile, new byte[]{7, 7, 7, 7, 7},
                StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        assertTrue(Files.size(dataFile) > goodSize, "file should be larger after corruption");

        try (KVStore store = new KVStore(dataFile)) {
            assertEquals("amy", new String(store.get("user:1"), StandardCharsets.UTF_8));
            assertEquals("bob", new String(store.get("user:2"), StandardCharsets.UTF_8));
            assertEquals(goodSize, Files.size(dataFile), "corrupt tail must be truncated back");
            store.put("user:3", "cara".getBytes(StandardCharsets.UTF_8));
        }

        try (KVStore store = new KVStore(dataFile)) {
            assertEquals("cara", new String(store.get("user:3"), StandardCharsets.UTF_8));
            assertEquals("amy",  new String(store.get("user:1"), StandardCharsets.UTF_8));
            ByteBuffer badHeader = ByteBuffer.allocate(20);
            badHeader.putInt(0);         // crc      (4)  — irrelevant; guard 2 fires before the CRC check
            badHeader.putLong(0L);       // timestamp(8)
            badHeader.putInt(9999);      // keySize  (4)  — the poison: claims 9999 bytes that aren't there
            badHeader.putInt(0);         // valSize  (4)
            Files.write(dataFile, badHeader.array(), StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            store.restore();

            ByteBuffer anotherBadHeader = ByteBuffer.allocate(20);
            anotherBadHeader.putInt(0);         // crc      (4)  — irrelevant; guard 2 fires before the CRC check
            anotherBadHeader.putLong(0L);       // timestamp(8)
            anotherBadHeader.putInt(-1);      // keySize  (4)  — the poison: claims 9999 bytes that aren't there
            anotherBadHeader.putInt(0);
            Files.write(dataFile, badHeader.array(), StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            store.restore();

            // after writing good records + closing, with goodSize = Files.size(dataFile):
            try (FileChannel fc = FileChannel.open(dataFile,
                    StandardOpenOption.READ, StandardOpenOption.WRITE)) {
                long pos = goodSize - 1;                     // last byte of the last record's value
                ByteBuffer b = ByteBuffer.allocate(1);
                fc.read(b, pos);                             // read the byte there
                byte flipped = (byte) (b.get(0) ^ 0xFF);     // invert its bits
                fc.write(ByteBuffer.wrap(new byte[]{flipped}), pos);   // write it back at the SAME position
            }
            store.restore();
        }
    }
}
