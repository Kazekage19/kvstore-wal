# kvstore

A persistent key-value store in Java, Bitcask-shaped: append-only data files, an in-memory index, and crash recovery by log replay.

## What it is

An embedded library — the same category as SQLite or RocksDB, not a server. Another program calls it:

```java
KVStore store = KVStore.open("/var/data/mystore");
store.put("user:1", "amy".getBytes());
store.close();

// process is killed. machine reboots. later:

KVStore store = KVStore.open("/var/data/mystore");
store.get("user:1");   // -> "amy"
```

Those last two lines are the point of the whole project. Everything else — the append-only file, the in-memory keydir, the CRC, the replay loop — exists to make that `get` return the right bytes after a crash instead of garbage.

## How it works

- **Writes append.** Nothing on disk is ever overwritten. A `put` to an existing key writes a new record at the end of the file and re-points the index; the old bytes are simply left behind.
- **The index lives in RAM.** A hash map of `key -> (fileId, offset, size)`. It holds no values, only where to find them, so a read is one map lookup and one disk seek.
- **Restart replays the log.** The map is gone after a crash but the data files aren't, so startup scans them forward and rebuilds it. Because records are in write order, the last one seen for a key is the newest — correct precedence for free.
- **Deletes are tombstones.** A delete appends a marker rather than erasing anything.
- **Compaction reclaims the dead bytes** left behind by updates and deletes, and writes hint files so the next startup doesn't have to scan values.

See [RECORD-FORMAT.md](RECORD-FORMAT.md) for the on-disk layout.

## Status

Early. Record format designed; `put` path in progress.

## Roadmap

- [ ] Record encode/decode + `put` — append and index
- [ ] `get` — index lookup and seek
- [ ] `delete` — tombstones
- [ ] File rollover
- [ ] Startup replay
- [ ] Crash tests — `kill -9` mid-write, restart, assert no acknowledged write is lost
- [ ] Compaction + hint files

## Build

```bash
mvn test
```

Java 17, JUnit 5.
