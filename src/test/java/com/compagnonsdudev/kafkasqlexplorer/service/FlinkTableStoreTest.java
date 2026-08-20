// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The store that keeps the {@code CREATE TABLE} statements written in the SQL editor.
 *
 * <p>What it exists to prevent is not an error but a <b>substitution</b>: the hand-written
 * definition died with the process, the next query on that name auto-registered a generated table
 * under it, and the query then ran — same name, rows returned, a watermark or a chosen subset of
 * columns quietly gone. So most of what is pinned here is about keying and removal, the two things
 * that decide whether the right definition comes back.
 */
class FlinkTableStoreTest {

    @TempDir
    Path tempDir;

    private FlinkTableStore storeAt(Path path) {
        ExplorerConfig config = new ExplorerConfig();
        config.setFlinkTableStorePath(path == null ? "" : path.toString());
        return new FlinkTableStore(config);
    }

    private static final String ORDERS_DDL =
        "CREATE TABLE orders (id STRING, ts TIMESTAMP(3), "
            + "WATERMARK FOR ts AS ts - INTERVAL '5' SECOND) WITH ('connector' = 'kafka')";

    @Test
    void aDefinitionSurvivesTheProcessThatWroteIt() {
        Path path = tempDir.resolve("tables.json");
        assertEquals("orders", storeAt(path).remember(ORDERS_DDL));

        List<FlinkTableStore.StoredTable> restored = storeAt(path).all();
        assertEquals(1, restored.size());
        assertEquals("orders", restored.get(0).name());
        assertEquals(ORDERS_DDL, restored.get(0).sql());
    }

    /** Rewriting a table is how it is edited — keeping both would replay the older one at boot. */
    @Test
    void rewritingATableReplacesItRatherThanAddingASecond() {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);

        store.remember(ORDERS_DDL);
        store.remember("CREATE TABLE orders (id STRING, total DECIMAL(10,2)) WITH ('connector' = 'kafka')");

        List<FlinkTableStore.StoredTable> all = store.all();
        assertEquals(1, all.size());
        assertTrue(all.get(0).sql().contains("DECIMAL"), "the newer definition won");
    }

    /**
     * The boot replay goes back through the same execution path, so it re-offers every statement.
     * Without this, each restart rewrote the file once per table and bumped every {@code savedAt}
     * — a field that would then mean "last restored" while being named for when it was written.
     */
    @Test
    void storingTheSameDefinitionAgainChangesNothing() throws IOException {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);
        store.remember(ORDERS_DDL);
        long savedAt = store.all().get(0).savedAt();
        byte[] before = Files.readAllBytes(path);

        assertEquals("orders", store.remember(ORDERS_DDL), "still reports the table it is about");

        assertEquals(savedAt, store.all().get(0).savedAt());
        assertArrayEquals(before, Files.readAllBytes(path));
    }

    @Test
    void ifNotExistsAndQualifiedNamesResolveToTheSameTable() {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);

        store.remember(ORDERS_DDL);
        store.remember("CREATE TABLE IF NOT EXISTS `default_catalog`.`default_database`.`orders` "
            + "(id STRING) WITH ('connector' = 'kafka')");

        assertEquals(1, store.all().size(),
            "the same table under a qualified name is the same key, or both would be replayed");
    }

    @Test
    void aBacktickedNameIsStoredUnquoted() {
        Path path = tempDir.resolve("tables.json");
        assertEquals("my_orders",
            storeAt(path).remember("CREATE TABLE `my_orders` (id STRING) WITH ('connector'='kafka')"));
    }

    /**
     * {@code TEMPORARY} is the author saying the table belongs to this session. Storing it would
     * contradict what they wrote.
     */
    @Test
    void aTemporaryTableIsNotKept() {
        Path path = tempDir.resolve("tables.json");

        assertNull(storeAt(path).remember(
            "CREATE TEMPORARY TABLE scratch (id STRING) WITH ('connector' = 'datagen')"));
        assertTrue(storeAt(path).all().isEmpty());
    }

    @Test
    void somethingThatIsNotACreateTableIsNotKept() {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);

        assertNull(store.remember("SELECT * FROM orders"));
        assertNull(store.remember("CREATE VIEW v AS SELECT 1"));
        assertNull(store.remember(null));
        assertTrue(store.all().isEmpty());
    }

    /**
     * A name that cannot safely be a log line, a JSON key and the body of a {@code DROP TABLE} is
     * not kept at all.
     *
     * <p>A backtick-quoted Flink identifier may contain very nearly anything, a newline included,
     * and this name goes on to be all three of those. Logging it verbatim is log injection: the
     * forged line lands in the file that is meant to be the record of what happened. Refused where
     * it enters, not cleaned up at each place it is later used.
     */
    @Test
    void aNameThatCouldForgeALogLineIsNotStored() {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);

        assertNull(store.remember("CREATE TABLE `orders\nINFO  legitimate-looking forged line` "
            + "(id STRING) WITH ('connector' = 'kafka')"));
        assertNull(store.remember("CREATE TABLE `orders\r\nWARN  another one` (id STRING)"));
        assertTrue(store.all().isEmpty());
    }

    /**
     * A quoted name is read whole, spaces included.
     *
     * <p>The pattern used to stop at the first whitespace, so {@code CREATE TABLE `weird name`} was
     * filed under the key {@code weird}: a different table from the one that had just been created.
     * Nothing would have replaced it on a rewrite, and {@code forget("weird name")} could not have
     * removed it. It is refused now — but it had to be *seen* whole before it could be refused.
     */
    @Test
    void aQuotedNameIsReadWholeRatherThanUpToTheFirstSpace() {
        FlinkTableStore store = storeAt(tempDir.resolve("tables.json"));

        assertNull(store.remember("CREATE TABLE `weird name` (id STRING)"));
        assertTrue(store.all().isEmpty(), "and nothing was filed under a truncated key");
    }

    /** The table still works for this process — what is declined is remembering it. */
    @Test
    void refusingToStoreANameIsNotRefusingTheStatement() {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);

        assertNull(store.remember("CREATE TABLE `weird name` (id STRING)"));
        assertEquals("orders", store.remember(ORDERS_DDL), "and the store still works afterwards");
    }

    // ── Removal ───────────────────────────────────────────────────────────────

    /**
     * Restarting used to be the only way to clear the catalogue, so keeping definitions takes that
     * escape hatch away. A store that could only grow would be a worse defect than the one it
     * fixes.
     */
    @Test
    void aDefinitionCanBeForgotten() {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);
        store.remember(ORDERS_DDL);

        assertTrue(store.forget("orders"));
        assertTrue(store.all().isEmpty());
        assertTrue(storeAt(path).all().isEmpty(), "and it stays forgotten across a restart");
    }

    /** "Forgotten" and "there was nothing to forget" are different answers to the same request. */
    @Test
    void forgettingSomethingThatWasNeverStoredSaysSo() {
        assertFalse(storeAt(tempDir.resolve("tables.json")).forget("never_created"));
    }

    @Test
    void aNameReadOffTheScreenIsMatchedWhateverItsCase() {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);
        store.remember(ORDERS_DDL);

        assertTrue(store.forget("ORDERS"));
    }

    // ── Bounds and bad files ──────────────────────────────────────────────────

    @Test
    void theStoreIsBoundedAndDropsTheOldestFirst() {
        Path path = tempDir.resolve("tables.json");
        FlinkTableStore store = storeAt(path);
        for (int i = 0; i < FlinkTableStore.MAX_TABLES + 5; i++) {
            store.remember("CREATE TABLE t" + i + " (id STRING) WITH ('connector' = 'kafka')");
        }

        List<FlinkTableStore.StoredTable> all = store.all();
        assertEquals(FlinkTableStore.MAX_TABLES, all.size());
        assertEquals("t5", all.get(0).name(), "the five oldest went");
        assertEquals("t" + (FlinkTableStore.MAX_TABLES + 4), all.get(all.size() - 1).name());
    }

    @Test
    void anUnknownFormatVersionIsIgnoredRatherThanGuessedAt() throws IOException {
        Path path = tempDir.resolve("tables.json");
        Files.writeString(path, "{\"version\":99,\"tables\":[{\"name\":\"x\",\"sql\":\"CREATE TABLE x\"}]}");

        assertTrue(storeAt(path).all().isEmpty());
    }

    @Test
    void anUnreadableFileCostsTheDefinitionsAndNotTheBoot() throws IOException {
        Path path = tempDir.resolve("tables.json");
        Files.writeString(path, "{ not json");

        FlinkTableStore store = storeAt(path);
        assertTrue(store.all().isEmpty());
        assertEquals("orders", store.remember(ORDERS_DDL), "and the next write repairs it");
    }

    /** One unreadable entry must not cost the rest of the file. */
    @Test
    void anEntryWithNoSqlIsSkippedAndTheOthersAreKept() throws IOException {
        Path path = tempDir.resolve("tables.json");
        Files.writeString(path, "{\"version\":1,\"tables\":["
            + "{\"name\":\"broken\"},"
            + "{\"name\":\"orders\",\"sql\":\"CREATE TABLE orders (id STRING)\",\"savedAt\":1}]}");

        List<FlinkTableStore.StoredTable> all = storeAt(path).all();
        assertEquals(1, all.size());
        assertEquals("orders", all.get(0).name());
    }

    @Test
    void aStoreWithNoPathKeepsNothing() {
        FlinkTableStore store = storeAt(null);

        assertFalse(store.isEnabled());
        assertNull(store.remember(ORDERS_DDL));
        assertFalse(store.forget("orders"));
    }

    /** The one switch answers for both stores: it is the same question about the same deployment. */
    @Test
    void theSettingsPersistenceSwitchTurnsThisOffToo() {
        ExplorerConfig config = new ExplorerConfig();
        config.setFlinkTableStorePath(tempDir.resolve("tables.json").toString());
        config.setSettingsPersistence(false);

        assertFalse(new FlinkTableStore(config).isEnabled());
    }

    // ── The identifier guard ──────────────────────────────────────────────────

    /**
     * The name goes back into {@code DROP TABLE `…`}, so what it may contain is not a matter of
     * taste: a backtick in a path variable is SQL injection into an engine that runs whatever DDL
     * it is handed.
     */
    @Test
    void onlyANameThatCanSafelyGoBackIntoAStatementIsAccepted() {
        assertTrue(FlinkTableStore.isSafeIdentifier("demo_orders_1"));
        assertTrue(FlinkTableStore.isSafeIdentifier("_private$1"));

        assertFalse(FlinkTableStore.isSafeIdentifier("orders`; DROP TABLE users; --"));
        assertFalse(FlinkTableStore.isSafeIdentifier("orders'"));
        assertFalse(FlinkTableStore.isSafeIdentifier("demo.orders"));
        assertFalse(FlinkTableStore.isSafeIdentifier("orders x"));
        assertFalse(FlinkTableStore.isSafeIdentifier("1orders"));
        assertFalse(FlinkTableStore.isSafeIdentifier(""));
        assertFalse(FlinkTableStore.isSafeIdentifier(null));
        assertFalse(FlinkTableStore.isSafeIdentifier("x".repeat(200)));
    }
}
