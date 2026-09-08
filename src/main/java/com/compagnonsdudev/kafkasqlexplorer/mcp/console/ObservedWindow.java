// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.console;

import java.time.Instant;

/**
 * What the console's numbers actually rest on, as against what was asked for.
 *
 * <p>This record is principle P1 turned on the supervision screen itself. A card headed "24 h"
 * computed from a ring buffer that only reaches back forty minutes is a wrong number wearing a
 * right label, and it is wrong in the confident direction: an operator reads "12 calls today" and
 * concludes the agent is quiet. The ring is bounded on purpose — it is a live feed, not a history
 * store, and the audit topic is what history is for — so the honest move is to say how far back it
 * really goes and how much it dropped.
 *
 * @param requestedWindowMs the window the caller asked for
 * @param oldestCallAt      the oldest call still held, null when nothing is held
 * @param callsHeld         how many calls the ring currently holds
 * @param ringCapacity      {@code explorer.mcp.console.ring-buffer-size}
 * @param droppedFromRing   calls evicted since startup — history that is gone from here
 * @param auditPersisted    whether anything outlives the ring at all
 */
public record ObservedWindow(
        long requestedWindowMs,
        Instant oldestCallAt,
        int callsHeld,
        int ringCapacity,
        long droppedFromRing,
        boolean auditPersisted
) {

    /**
     * True when the ring genuinely covers the window asked for.
     *
     * <p>Two ways it can fail, and they are different sentences: the ring never filled (so it
     * covers everything there was, and the window is complete), or it filled and evicted (so the
     * window is a suffix of the truth). Only the second is a hole, which is why the eviction count
     * decides it rather than the oldest timestamp.
     */
    public boolean coversRequestedWindow() {
        return droppedFromRing == 0;
    }
}
