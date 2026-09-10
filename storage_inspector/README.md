# MAPS storage inspector

Read-only offline inspection and NDJSON export of dynamic_storage version 1 partition stores. Tracked by MSG-275.

## Build and run

Requires Java 21 and Maven, consistent with maps_apps:

```bash
mvn -pl storage_inspector -am clean package
storage_inspector/bin/maps-storage-inspector --input /srv/maps-snapshot --report /tmp/storage-report.ndjson
```

The launcher uses the shaded application JAR and `target/lib/dynamic_storage-*.jar`. It limits the JVM heap to 512 MiB. Keep these files together with the launcher, or set `MAPS_STORAGE_INSPECTOR_JAR` and use the installed server libraries below.

To validate and export messages, supply the matching **server JAR and its dependency JARs**. This uses the real `MessageFactory.unpack(ByteBuffer[])`; it does not start MAPS:

```bash
export MAPS_SERVER_CLASSPATH='/opt/maps/lib/*:/opt/maps/maps.jar'
storage_inspector/bin/maps-storage-inspector \
  --input /srv/maps-snapshot \
  --output /tmp/storage-events.ndjson \
  --report /tmp/storage-report.ndjson
```

Adjust the classpath to the installation layout. The installed dynamic_storage takes precedence over the bundled build copy. Use the same server version/configuration that wrote the data, particularly for compressed message payloads. An executable JAR with nested dependencies must be unpacked to ordinary classpath JARs first.

`--decode` validates message decoding without writing an event file. Without `--decode` or `--output`, checks are structural and require only dynamic_storage, not the server.

## What it reports

- Recursive discovery of `partition_<number>_index` and matching `_index_data` files, grouped by store directory in the final count.
- Per-partition index/data state, logical file size in bytes, key range, active index entries, expired entries, deleted/unused slots, validated/decoded counts and referenced bytes.
- File magic/version, open/closed markers, key ranges and truncated index slots.
- Data offsets, index/frame length agreement, buffer count/length bounds and message key/expiry agreement when decoding.
- A physical scan of the data file, including data no longer referenced by the active index, to detect incomplete append tails.
- Missing data, orphan data files, unreadable paths, unsupported partition files, archive placeholders and changes observed during scanning.

Reports are NDJSON (`finding`, `store`, `physicalData`, `summary`) on stderr or in `--report`. Event output is separate. Findings include file, error code, byte offset where available, and details; invalid records are skipped while inspection continues with the next index slot.

Exit codes: **0** checks completed, **1** corruption/read failure, **2** usage/runtime/output failure, **3** warnings or incomplete coverage. `CHECKED` with `STRUCTURAL_ONLY` does not assert that messages were decoded or that payload content is correct.

## Event format and DuckDB

One object per active index entry, retaining expired entries for investigation. Deleted/unindexed physical records are checked structurally but not exported as active messages. Output streams one record at a time; reports do not accumulate findings in memory. The default per-record limit is 67108864 bytes and can be changed with `--max-record-bytes`.

Exports contain `topic`, `identifier`, `creation`, `expiry`, `delayed`, priority/QoS, retain/UTF-8 flags, response topic, content type, schema ID, metadata and typed data. `opaqueData` and `correlationData` preserve bytes as base64. `correlationDataByteArray` preserves the original correlation interpretation. `storageFile`, `storageKey`, `storageOffset`, `storageLength` and `storageExpired` provide provenance. Transient server state is not invented.

The topic is read safely from `resource.yaml` in the store directory or its destination parent for `message.data` stores. If unavailable it is JSON null; `storageFile` still identifies the store. For a single store, `--topic /known/topic` provides an explicit override. No topic is inferred from a UUID directory name.

The existing ndjson_viewer reads `topic` and base64 `opaqueData` into its `maps_log` view. Load the export with that app's `--input` option. Its payload decoding expression is:

```sql
SELECT topic, identifier,
       try_cast(try(decode(from_base64(opaqueData))) AS JSON) AS payload
FROM read_ndjson_auto('/tmp/storage-events.ndjson', union_by_name = true);
```

## Evidence preservation and limits

All input channels use READ and reject symlinks. The normal storage loader is deliberately not instantiated: it changes open/closed flags and can remove expired entries while loading. The library's `IndexRecord` decodes slots; file framing is read with explicit bounds checks.

Use a stopped-server copy or filesystem snapshot for consistent evidence. Open markers are warnings, not proof of corruption. Size/mtime/file-key comparisons detect some concurrent changes but cannot make a live scan atomic. Output files must be new and outside the input tree. A failed run may leave partial NDJSON; retain its matching report and check the exit code before treating the export as complete.

Compressed, migrated and S3 archive placeholders are explicitly uninspected; no archive is restored or remote object fetched. Other MAPS state files (subscriptions, retained-message state, transaction state, STANAG task state) are outside this partition inspector's scope. Unknown versions and locations are rejected. The on-disk format has no per-message checksum: valid framing cannot prove payload bytes are unaltered, nor establish the cause of corruption. Index-reference overlap/duplicate detection and recovery of orphan/deleted messages are not implemented.

Record limits bound storage-frame allocation, but the external server decoder controls nested payload parsing and decompression. Malformed nested data or a decompression bomb can still exhaust the process heap; structural inspection is available independently. The JVM heap limit bounds the process, not the validity of that external decoder.

## Validation

`mvn -pl storage_inspector -am test` runs structural fixtures covering valid/empty, corrupt/truncated, hostile lengths, open states, archives, recursive discovery, orphan files, safe topic metadata, source preservation and output protection. Export mapping tests cover JSON and binary payloads, metadata and key mismatch using a synthetic `StorableFactory`.

Real server-JAR deserialization, compressed message compatibility and an end-to-end ndjson_viewer import require the matching installed server and its dependencies. Those integration checks should be run against a representative offline store before operational use.
