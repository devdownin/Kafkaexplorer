// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Set;

/**
 * Reading and replacing the small JSON files this application keeps under {@code data/}.
 *
 * <p>Two stores need exactly the same thing and for the same reasons — {@link SettingsStore} for
 * what the Settings page is used to enter, {@link FlinkTableStore} for the table definitions
 * written in the SQL editor — so it is defined once. Two implementations of one behaviour drift,
 * and here they would drift on the two properties that are the whole point: that the file is
 * never observed half-written, and that it is not world-readable.
 *
 * <p><b>Replaced, never written in place.</b> The one moment either file is read is a boot, which
 * is precisely when an interrupted write from the previous run would surface — as a settings file
 * that parses into half a connection.
 *
 * <p><b>Owner-only.</b> Both files can hold credentials: the settings store an SSL keystore
 * password and an LLM API key, and a hand-written {@code CREATE TABLE} embeds Kafka client
 * properties, which is why {@code DdlGeneratorService.maskSensitiveProperties} exists for
 * everything that leaves through the API. {@code data/} is a volume an operator may mount
 * alongside other things.
 */
final class JsonStoreFile {

    private static final Logger log = LoggerFactory.getLogger(JsonStoreFile.class);

    private JsonStoreFile() {
    }

    /**
     * The file's contents as a tree, or {@code null} when there is nothing usable to read.
     *
     * <p>Never throws: an absent, empty or corrupt store is a store with nothing in it, and losing
     * a boot over one would be a worse outcome than losing what it held.
     *
     * @param what named in the log line, since "a JSON file could not be read" says nothing about
     *             which of the operator's two sets of hand-entered state has just gone missing
     */
    static JsonNode read(ObjectMapper mapper, Path path, String what) {
        if (path == null || !Files.isRegularFile(path)) return null;
        try {
            byte[] bytes = Files.readAllBytes(path);
            if (bytes.length == 0) return null;
            return mapper.readTree(bytes);
        } catch (Exception e) {
            log.warn("Ignoring {}: {} could not be read ({}). It is left on disk to be looked at, "
                + "and the next save replaces it.", path, what, SqlErrorClassifier.explain(e));
            return null;
        }
    }

    /**
     * Replaces {@code path} with {@code content}, atomically where the filesystem allows it.
     *
     * <p>The temporary file is created in the target's own directory, so the move is a rename
     * within one filesystem rather than a copy, and it is given its permissions <em>before</em>
     * anything is written into it — otherwise the credentials exist world-readable for as long as
     * the write takes.
     */
    static void replace(ObjectMapper mapper, Path path, Object content) throws IOException {
        Path parent = path.getParent();
        if (parent != null) Files.createDirectories(parent);

        Path temp = Files.createTempFile(parent != null ? parent : path, ".store-", ".json.tmp");
        try {
            restrictToOwner(temp);
            Files.write(temp, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(content));
            try {
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                // Some bind-mounted and network filesystems cannot. A replace is still much better
                // than writing in place, and this is not worth failing a save over.
                Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictToOwner(path);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /**
     * Owner-only permissions, best-effort.
     *
     * <p>Silent on a filesystem without POSIX permissions — refusing to persist on Windows would
     * trade the whole feature for a guarantee that platform does not express in these terms
     * anyway.
     */
    private static void restrictToOwner(Path target) {
        try {
            Files.setPosixFilePermissions(target,
                Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException | IOException ignored) {
            // Not a POSIX filesystem, or the permissions cannot be set — see above.
        }
    }
}
