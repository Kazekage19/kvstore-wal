# kvstore

A persistent key-value store in Java, Bitcask-shaped: append-only data files, an in-memory index, and crash recovery by log replay.

## What it is

An embedded library — the same category as SQLite or RocksDB, not a server. Another program calls it:

```java
try (KVStore store = new KVStore(Path.of("/var/data/mystore.db"))) {
    store.put("user:1", "amy".getBytes(StandardCharsets.UTF_8));
    byte[] value = store.get("user:1");   // -> "amy"
}
```

The point of the project is the harder version:

```java
// the process is killed mid-run (kill -9, OOM, crash). later:

try (KVStore store = new KVStore(Path.of("/var/data/mystore.db"))) {
    store.get("user:1");   // -> "amy"
}
```

## How it works

- **Writes append.** Nothing on disk is ever overwritten. A `put` to an existing key writes a new record at the end of the file and re-points the index; the old bytes are simply left behind.
- **The index lives in RAM.** A hash map of `key -> (fileId, offset, size)`. It holds no values, only where to find them, so a read is one map lookup and one disk seek.
- **Restart replays the log.** The map is gone after a crash but the data files aren't, so startup scans them forward and rebuilds it. Because records are in write order, the last one seen for a key is the newest — correct precedence for free.
- **A torn tail is cut off, not trusted.** Replay checks every record in order: is there a full header, are the sizes possible, does the CRC match. At the first record that fails, it truncates the file at that offset and stops. That is safe because the file is append-only: a crash can only damage the *end*, so nothing valid can sit after a bad record.
- **Deletes are tombstones.** *(not implemented)* A delete appends a marker rather than erasing anything.
- **Compaction reclaims the dead bytes** *(not implemented)* left behind by updates and deletes, and writes hint files so the next startup doesn't have to scan values.

See [RECORD-FORMAT.md](RECORD-FORMAT.md) for the on-disk layout.

## Durability — what this actually guarantees

Stated precisely, because a store that overclaims here is worse than one that admits its limits.

**`put()` does not call `fsync`.** It returns as soon as `write()` has copied the record into the OS page cache. The kernel writes those pages to disk later, on its own schedule.

| Failure | Survives? | Why |
|---|---|---|
| Process killed (`kill -9`) | **Yes** | `write()` copies bytes into the OS page cache, which belongs to the kernel, not the process. Killing the process does not touch it; the OS still flushes those pages. |
| Power loss / kernel panic | **No** | Nothing forces the page cache to disk, so any write the kernel had not flushed yet is lost — on every OS. Replay then sees a shorter (possibly torn) file and recovers up to the last intact record. |
| Bit rot / torn record | Detected | Every record carries a CRC32 over its own bytes. On startup, replay truncates the file at the first bad record. At read time, `get()` recomputes the CRC before interpreting any field and throws `IOException` on mismatch rather than returning damaged data. |
| Index pointing at the wrong record | Detected | The key is stored inside the record and compared against the requested key. An intact, CRC-valid record belonging to a *different* key throws `IllegalStateException` — that condition means a bug in this code, not a disk fault, so it is never silently repaired. |

### What surviving power loss would take

- **Call `channel.force(false)` in `put()`.** That maps to `fdatasync`: the record's bytes plus the metadata needed to find them (file size, block pointers), skipping `mtime`.
- **It costs one device flush per write**, capping throughput at the drive's flush rate — roughly 1k–10k writes/sec on consumer NVMe. Group commit (batch N writes behind one flush, then release all N callers) is the standard fix.
- **macOS needs more than that.** `fsync` there does not flush the drive's own volatile cache; only `F_FULLFSYNC` does, and Java has no API for it. On Linux, `fsync` does issue the flush.
- **Directory durability.** `fdatasync` covers the file, not its parent directory entry. Once file rollover creates new data files, the directory must be fsync'd too, or a new file can vanish after a crash.

## Status

`put`, `get`, startup replay and corrupt-tail recovery work and are covered by tests. Deletes, rollover and compaction are open.

- [x] Record encode/decode + `put` — append and index
- [x] `get` — index lookup, positional seek, CRC verification, key identity check
- [ ] `delete` — tombstones
- [ ] File rollover
- [x] Startup replay — rebuild the keydir from the log on open (`testRestore`)
- [x] Corrupt-tail recovery — truncate at the first bad record; fault-injection test covers a torn header, an oversized key size, a negative key size and a CRC mismatch (`restoreTruncatesCorruptTail`)
- [ ] Real crash test — `kill -9` a separate process mid-write, restart, assert no acknowledged write is lost (the fault-injection test corrupts bytes directly; it does not kill a process)
- [ ] Compaction + hint files

## Build

```bash
mvn test
```

Java 17, JUnit 5.
