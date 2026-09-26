// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.tools;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A single complete lag reading, keyed by topic and group. No payloads or credentials are stored. */
public interface LagSampleStore {
    record Sample(long lag, long atMs, Map<Integer, Long> endOffsets,
                  Map<Integer, Long> committedOffsets) { }

    /** Atomically replace a reading and return its unexpired predecessor. */
    Sample swap(String key, Sample sample, long ttlMs) throws IOException;

    static LagSampleStore inMemory() {
        return new LagSampleStore() {
            private final Map<String, Sample> readings = new ConcurrentHashMap<>();
            @Override public Sample swap(String key, Sample sample, long ttlMs) {
                readings.entrySet().removeIf(entry -> sample.atMs() - entry.getValue().atMs() > ttlMs);
                if (readings.size() >= 1000 && !readings.containsKey(key)) readings.clear();
                Sample old = readings.put(key, sample);
                return old != null && sample.atMs() - old.atMs() <= ttlMs ? old : null;
            }
        };
    }

    /** Instances sharing the same mounted directory share the baseline, including across restarts. */
    static LagSampleStore shared(Path directory) {
        return new FileStore(directory);
    }

    final class FileStore implements LagSampleStore {
        private static final int MAGIC = 0x4b455831;
        private static final Map<Path, Object> LOCAL_LOCKS = new ConcurrentHashMap<>();
        private final Path directory;

        private FileStore(Path directory) { this.directory = directory.toAbsolutePath().normalize(); }

        @Override public Sample swap(String key, Sample sample, long ttlMs) throws IOException {
            Files.createDirectories(directory);
            Path file = directory.resolve(hash(key) + ".lag");
            if (!Files.exists(file)) {
                try (var entries = Files.list(directory)) {
                    if (entries.limit(10_001).count() >= 10_000) {
                        throw new IOException("lag history directory reached its 10000-key limit");
                    }
                }
            }
            // FileLock coordinates processes; the JVM lock prevents overlapping locks in one process.
            synchronized (LOCAL_LOCKS.computeIfAbsent(file, ignored -> new Object())) {
                try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE,
                        StandardOpenOption.READ, StandardOpenOption.WRITE);
                     var lock = channel.lock()) {
                    Sample old = read(channel);
                    byte[] next = encode(sample);
                    channel.truncate(0);
                    channel.position(0);
                    ByteBuffer buffer = ByteBuffer.wrap(next);
                    while (buffer.hasRemaining()) channel.write(buffer);
                    channel.force(true);
                    return old != null && sample.atMs() >= old.atMs()
                            && sample.atMs() - old.atMs() <= ttlMs ? old : null;
                }
            }
        }

        private static Sample read(FileChannel channel) throws IOException {
            if (channel.size() == 0) return null;
            if (channel.size() > 100_000) throw new IOException("lag snapshot exceeds size limit");
            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            channel.position(0);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { /* read full snapshot */ }
            if (buffer.hasRemaining()) throw new IOException("incomplete lag snapshot");
            try (var in = new DataInputStream(new ByteArrayInputStream(buffer.array()))) {
                if (in.readInt() != MAGIC) throw new IOException("invalid lag snapshot format");
                long lag = in.readLong(), at = in.readLong();
                int count = in.readInt();
                if (count < 0 || count > 1000) throw new IOException("invalid partition count");
                Map<Integer, Long> ends = new LinkedHashMap<>(), commits = new LinkedHashMap<>();
                for (int i = 0; i < count; i++) {
                    int partition = in.readInt();
                    ends.put(partition, in.readLong());
                    commits.put(partition, in.readLong());
                }
                return new Sample(lag, at, ends, commits);
            }
        }

        private static byte[] encode(Sample sample) throws IOException {
            if (sample.endOffsets().size() > 1000) throw new IOException("too many partitions for lag snapshot");
            var bytes = new ByteArrayOutputStream();
            try (var out = new DataOutputStream(bytes)) {
                out.writeInt(MAGIC);
                out.writeLong(sample.lag());
                out.writeLong(sample.atMs());
                out.writeInt(sample.endOffsets().size());
                for (var entry : sample.endOffsets().entrySet()) {
                    out.writeInt(entry.getKey());
                    out.writeLong(entry.getValue());
                    out.writeLong(sample.committedOffsets().get(entry.getKey()));
                }
            }
            return bytes.toByteArray();
        }

        private static String hash(String key) {
            try {
                return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                        .digest(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }
}
