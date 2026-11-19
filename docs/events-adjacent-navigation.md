# Recording Previous/Next across pages

Events uses `/api/recordings/adjacent?fromId=<32-hex-id>&step=-1|1` at a page edge.
The endpoint accepts the same library filters as `/api/recordings` and an optional
`pageSize` (1–200). It returns a stable `id`, `status`, and the matched recording;
when `pageSize` is supplied it also returns the destination's 1-based list `page`.
The player changes immediately; the list refreshes independently to that page.
Steps within the currently loaded page do not call the daemon. Parking's external
player continues to use its local list.

| HTTP | Status | Meaning |
| --- | --- | --- |
| 200 | `found` | `recording` is the next filtered recording |
| 200 | `end` | No matching clip in the requested direction |
| 404 | `missing_anchor` | Clip deleted or on an offline volume |
| 503 | `error` | Database query failed (not end-of-list) |
| 200 | `warming` flag and `success:false` | Index not yet initialized / warming |
| 503 | `indexUnavailable` and `success:false` | Index is unavailable |

The UI latches a boundary **only** for an authoritative `end`. It keeps the
loaded list as the fallback on errors. Closing the player or opening a different
clip invalidates late replies. Watched state belongs to the playing recording,
not the visible page: playback for 2.5 seconds can mark a clip watched even
while the destination page loads in the background.

## H2 plan check (manual, not included in project test runs)

`RecordingsIndexSchema` creates `idx_rec_ts_rank_id` on `(ts_ms DESC,
root_rank ASC, recording_id ASC)` for the normal list and older-direction seek,
and `idx_rec_ts_rank_id_asc` on `(ts_ms ASC, root_rank DESC,
recording_id DESC)` for newer-direction seeks. Both directions include an
explicit `ts_ms <= ?` or `ts_ms >= ?` bound before the tie conditions.

On a populated device, use H2 `EXPLAIN ANALYZE` for these shapes (replace
placeholders with actual values of the corresponding types and include the
normal filter `WHERE` conditions):

```sql
EXPLAIN ANALYZE SELECT * FROM recordings USE INDEX (idx_rec_ts_rank_id)
WHERE is_available = TRUE
ORDER BY ts_ms DESC, root_rank ASC, recording_id ASC LIMIT 12 OFFSET 0;

EXPLAIN ANALYZE SELECT * FROM recordings USE INDEX (idx_rec_ts_rank_id_asc)
WHERE is_available = TRUE AND ts_ms >= 1700000000000
  AND (ts_ms > 1700000000000 OR (ts_ms = 1700000000000 AND root_rank < 1)
       OR (ts_ms = 1700000000000 AND root_rank = 1
           AND recording_id < 'ffffffffffffffffffffffffffffffff'))
ORDER BY ts_ms ASC, root_rank DESC, recording_id DESC LIMIT 1;
```

Confirm `/* index sorted */` appears in the plan and that the indexed
scan's row counts stay bounded. Compare filtered cases, equal timestamp/rank
ties, both boundaries, offline anchor, cold boot, query failure, page crossing,
watched updates, stale replies, and Parking playback. Performance timings from
PR #840 are prior maintainer measurements, not results of this change.
