# Logs panel: bounded history and batched publication

The native Diagnostics Logs panel retains the most recent 500 entries in arrival order, including DEBUG entries that are filtered out of the expensive file/console sinks. App and daemon logging settings and file durability are unaffected.

The history is updated in constant time using a deque. The `logs` and `filteredLogs` LiveData lists are presentation snapshots, not the history itself: they are empty without active observers and are rebuilt immediately when observed again. Filter matching remains a case-insensitive tag substring. `getAvailableTags()` reads the retained history independently of the observer lifecycle.

While a list has active observers, incoming logs schedule one main-thread refresh after 250 ms; further arrivals join the pending batch. Filter changes and clearing the history request an immediate refresh. The final observer becoming inactive cancels pending work. The main looper or a busy UI may delay publication beyond the nominal interval, and intermediate UI snapshots are deliberately omitted. A collapsed panel that still has an active lifecycle observer still receives batched updates.

This reduces snapshot allocation and RecyclerView update pressure without changing the retained history capacity. Verify on a parked device by generating a burst of logs, navigating away and back, changing filters, clearing the panel and checking the latest 500 entries. Compare allocations and UI frame timing with the previous version.
