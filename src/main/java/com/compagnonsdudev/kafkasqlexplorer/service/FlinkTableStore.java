// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.compagnonsdudev.kafkasqlexplorer.config.ExplorerConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The {@code CREATE TABLE} statements an operator wrote by hand, kept so a restart does not
 * quietly replace them with generated ones.
 *
 * <p>Flink's catalogue is in-memory, so every table this application knows about died with the
 * process. For most of them that costs nothing: {@code FlinkSqlService.autoRegisterTableIfNeeded}
 * re-derives a table from its Kafka topic the next time a query names it, which is the whole point
 * of auto-registration. A table an operator <em>typed</em> is the exception, and it failed in the
 * worst available way — not with an error, but with a substitution. The hand-written definition
 * disappeared, the next query on that name auto-registered a <em>generated</em> one under it, and
 * the query then ran: same name, rows returned, and a watermark, a chosen subset of columns, a
 * format option or a connector property silently gone. Nothing anywhere said the definition had
 * changed.
 *
 * <p><b>Only what was typed.</b> Auto-registration writes its DDL through
 * {@code executeMutationSql} and never reaches here, so the store holds exactly the statements
 * that came through {@code POST /api/query} — the ones that cost someone the effort of writing
 * them, and the only ones a generated definition is not an acceptable stand-in for.
 *
 * <p><b>Keyed by table name, so a rewrite replaces.</b> Running {@code CREATE TABLE orders} again
 * with different options is how the definition is edited; keeping both versions would replay the
 * older one on the next boot and make the outcome depend on file order.
 *
 * <p><b>Removable.</b> Restarting used to be the only way to clear a table from the catalogue, so
 * persisting it takes that escape hatch away — {@code DELETE /api/query/table/{name}} gives it
 * back, dropping the table and forgetting it in one gesture. A store that could only grow would be
 * a worse defect than the one it fixes.
 */
@Component
public class FlinkTableStore {

    private static final Logger log = LoggerFactory.getLogger(FlinkTableStore.class);

    static final int FORMAT_VERSION = 1;

    /**
     * Bounded like every other store here. A definition is a few kilobytes and they are replayed
     * one at a time into the Flink runtime at boot, so the cost of the cap being reached is a slow
     * start; the oldest go first, and the eviction is logged rather than silent.
     */
    static final int MAX_TABLES = 100;

    /**
     * The table a {@code CREATE TABLE} declares.
     *
     * <p>{@code TEMPORARY} is matched so it can be recognised and <em>refused</em>: the word is the
     * author saying the table belongs to this session, and storing it would contradict what they
     * wrote. (The statement whitelist in {@code FlinkSqlService} does not admit it today; the guard
     * is here because this is where the rule belongs, not where it currently happens to be
     * enforced.)
     */
    private static final Pattern CREATE_TABLE = Pattern.compile(
        "^\\s*CREATE\\s+(TEMPORARY\\s+)?TABLE\\s+(IF\\s+NOT\\s+EXISTS\\s+)?([^\\s(]+)",
        Pattern.CASE_INSENSITIVE);

    /** What a name has to look like to be put back into SQL — see {@code FlinkSqlService.dropTable}. */
    static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_$]{0,127}");

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path path;

    /** Insertion-ordered: the replay follows the order the definitions were written in. */
    private final Map<String, StoredTable> tables = new LinkedHashMap<>();

    /**
     * @param name    the table as declared, unquoted
     * @param sql     the statement as it was executed, comments stripped and identifiers normalised
     * @param savedAt when it was last written
     */
    public record StoredTable(String name, String sql, long savedAt) {
    }

    public FlinkTableStore(ExplorerConfig explorerConfig) {
        this.path = explorerConfig.isSettingsPersistence()
            ? SettingsStore.resolvePath(explorerConfig.getFlinkTableStorePath())
            : null;
        load();
    }

    public boolean isEnabled() {
        return path != null;
    }

    /** The definitions held, oldest first — which is the order the boot replays them in. */
    public synchronized List<StoredTable> all() {
        return List.copyOf(tables.values());
    }

    /**
     * Keeps a statement that has just been executed successfully.
     *
     * <p>Best-effort in both directions: a statement whose table name cannot be read is not stored
     * (there would be no key to replace it under, and no way to forget it), and a write that fails
     * costs a warning rather than the query the operator was running. The table is registered in
     * Flink either way for the life of this process, which is exactly the behaviour that existed
     * before this store.
     *
     * @return the name it was stored under, or {@code null} when it was not stored
     */
    public synchronized String remember(String sql) {
        if (!isEnabled() || sql == null) return null;
        Matcher matcher = CREATE_TABLE.matcher(sql);
        if (!matcher.find()) return null;
        if (matcher.group(1) != null) return null;   // TEMPORARY — the author scoped it themselves
        String name = unquote(matcher.group(3));
        if (name == null || name.isBlank()) return null;

        // Storing the same definition again changes nothing, and saying so matters at boot: the
        // replay goes back through executeSql, so without this every restart rewrote the file once
        // per table, reordering nothing but bumping every savedAt — a field that would then mean
        // "last restored" while being named for when it was written.
        StoredTable existing = tables.get(name);
        if (existing != null && existing.sql().equals(sql)) return name;

        tables.remove(name);                          // re-inserted last: a rewrite is the newest
        tables.put(name, new StoredTable(name, sql, System.currentTimeMillis()));
        while (tables.size() > MAX_TABLES) {
            String evicted = tables.keySet().iterator().next();
            tables.remove(evicted);
            log.warn("Table definition '{}' is no longer kept: more than {} have been created in "
                + "the SQL editor, and the oldest are dropped. It stays registered for this "
                + "process and will not come back after a restart.", evicted, MAX_TABLES);
        }
        flush();
        return name;
    }

    /**
     * Stops keeping a definition.
     *
     * @return {@code true} when there was one — the caller says "forgotten" or "there was nothing
     *         to forget" rather than reporting both as success
     */
    public synchronized boolean forget(String name) {
        if (!isEnabled() || name == null) return false;
        StoredTable removed = tables.remove(name);
        if (removed == null) {
            // A table created before this store existed, or one Flink knows because it was
            // auto-registered. Matched case-insensitively as a courtesy, since the name most
            // likely arrived by being read off a screen.
            String match = tables.keySet().stream()
                .filter(key -> key.equalsIgnoreCase(name)).findFirst().orElse(null);
            if (match == null) return false;
            tables.remove(match);
        }
        flush();
        return true;
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    private void load() {
        JsonNode root = JsonStoreFile.read(mapper, path, "the table definitions written in the SQL editor");
        if (root == null) return;
        int version = root.path("version").asInt(-1);
        if (version != FORMAT_VERSION) {
            log.warn("Ignoring {}: it declares format version {}, and this build writes {}. The "
                + "table definitions it holds are not restored; creating a table in the SQL editor "
                + "rewrites the file.", path, version < 0 ? "none" : version, FORMAT_VERSION);
            return;
        }
        for (JsonNode node : root.path("tables")) {
            String name = node.path("name").asText(null);
            String sql = node.path("sql").asText(null);
            if (name == null || name.isBlank() || sql == null || sql.isBlank()) continue;
            tables.put(name, new StoredTable(name, sql, node.path("savedAt").asLong(0)));
        }
        if (!tables.isEmpty()) {
            log.info("{} table definition(s) written in the SQL editor will be restored from {}",
                tables.size(), path);
        }
    }

    private void flush() {
        try {
            ObjectNode root = mapper.createObjectNode();
            root.put("version", FORMAT_VERSION);
            root.put("comment", "CREATE TABLE statements executed in the Kafka Explorer SQL editor, "
                + "replayed into Flink at startup. Tables auto-registered from a Kafka topic are "
                + "not here: they are re-derived on demand.");
            ArrayNode array = root.putArray("tables");
            for (StoredTable table : tables.values()) {
                ObjectNode node = array.addObject();
                node.put("name", table.name());
                node.put("sql", table.sql());
                node.put("savedAt", table.savedAt());
            }
            JsonStoreFile.replace(mapper, path, root);
        } catch (Exception e) {
            // The table is registered in Flink for this process whatever happens here; what is
            // lost is only its return after a restart. Failing the operator's query over that
            // would be the wrong trade.
            log.warn("Table definitions were not written to {}: {}. Tables created in the SQL "
                + "editor will not come back after a restart.", path, SqlErrorClassifier.explain(e));
        }
    }

    /**
     * The bare table name: backticks removed, and the last segment of a qualified name kept.
     *
     * <p>{@code `my-catalog`.`db`.`orders`} is the same table as {@code orders} in the default
     * catalogue this application registers everything into, and the store has to key them the same
     * way or a rewrite would be filed as a second table and both replayed.
     */
    static String unquote(String identifier) {
        if (identifier == null) return null;
        String name = identifier.trim();
        if (name.isEmpty()) return null;
        List<String> segments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '`') {
                quoted = !quoted;
            } else if (c == '.' && !quoted) {
                segments.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        segments.add(current.toString());
        String last = segments.get(segments.size() - 1).trim();
        return last.isEmpty() ? null : last;
    }

    /** Names, for a log line that says which tables are involved. */
    static String namesOf(Collection<StoredTable> stored) {
        return stored.stream().map(StoredTable::name).collect(java.util.stream.Collectors.joining(", "));
    }

    /** Only what can go back into a statement — see {@code FlinkSqlService.dropTable}. */
    static boolean isSafeIdentifier(String name) {
        return name != null && SAFE_IDENTIFIER.matcher(name).matches()
            && !name.toUpperCase(Locale.ROOT).equals("TABLE");
    }
}
