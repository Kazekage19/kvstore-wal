package com.akshat.kvstore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
}
