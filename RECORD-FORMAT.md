# Record format — v0.1

*Designed by Akshat, 1 Aug 2026. This is the spec to type against on Sun 2 Aug — the thinking is already done.*

## On-disk layout of a single record

```
 0        4              12         16         20            20+K
 +--------+--------------+----------+----------+-------------+---------------+
 |  CRC32 |  timestamp   | keySize  | valSize  |     key     |     value     |
 | 4 bytes|   8 bytes    | 4 bytes  | 4 bytes  |  K bytes    |    V bytes    |
 +--------+--------------+----------+----------+-------------+---------------+
 └─ header: 20 bytes fixed ────────────────────┘└─ variable ─────────────────┘
```

**Total record size** = `20 + keySize + valSize`

## Field notes

| Field | Size | Purpose |
|---|---|---|
| `crc` | 4 B | Fingerprint of every byte *after* it. Written on `put`, recomputed on read. Mismatch = damaged record. |
| `timestamp` | 8 B | Millis since epoch. Not used for precedence (file order handles that) — useful for debugging and future TTL. |
| `keySize` | 4 B | Length of the key in bytes. |
| `valSize` | 4 B | Length of the value in bytes. A tombstone is `valSize = -1`. |
| `key` | K B | The key. Raw bytes, not a String on disk. |
| `value` | V B | Opaque blob. The store never inspects it. |

## Two properties that fall out of this layout

**Self-describing.** No separators, no index, no table of contents. Each record states its own length, so the next one begins at `currentOffset + 20 + keySize + valSize`. This is what makes a plain forward scan possible.

**Offset is never stored.** A record does not know where it lives. The offset is simply your read position during a scan. Do not add an offset field — it's redundant, and it will disagree with reality after a compaction.

## Decisions already made

- CRC goes **first**, covering everything after it. Matches Bitcask.
- Size field is the **value** length, not the total record length. Naming it `valSize` (never `byteSize`) so it can't be misread later.

## Open — decide with a fresh brain, not tonight

- Class boundaries. Who owns encoding a record, who owns the file handle, who owns the keydir, who decides when to compact. This is the LLD payoff of the project; it is worth an hour of deliberate thought.
- Sanity-bounding the size fields during replay. A corrupt `keySize` can send you reading nonsense *before* you reach the CRC check, so sizes must be checked against remaining file length first. Needed for crash tests, not for `put`.

## Sunday 2 Aug — scope

**Record format + the `put` path only.** Encode a record, append it to a file, update the in-memory map. That's it.

`get()` is next weekend unless `put` lands early and you're still fresh.
