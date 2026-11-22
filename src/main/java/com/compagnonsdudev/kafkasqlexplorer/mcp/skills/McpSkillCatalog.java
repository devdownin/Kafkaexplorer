// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.skills;

import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.regex.Pattern;

/**
 * Immutable distribution snapshot built from the same versioned SKILL.md files used by agents.
 *
 * <p>The Maven resources phase packages .agents/skills into mcp-skills/. The index is explicit so
 * a jar and an exploded classpath enumerate identically. Every entry is validated at startup,
 * never read from a caller-supplied filesystem path and never assembled from data found in Kafka.
 * The SHA-256 digest is calculated over the exact UTF-8 bytes served via resources/read.
 */
public final class McpSkillCatalog {
    private static final Pattern NAME = Pattern.compile("[a-z0-9]+(?:-[a-z0-9]+)*");
    private static final int MAX_SKILL_BYTES = 1_048_576;
    private static final String INDEX = "mcp-skills/catalog.txt";

    public record File(String uri, String digest, long size) {}
    public record Entry(String uri, Map<String, String> frontmatter, List<File> resources) {
        public String name() { return frontmatter.get("name"); }
        public String description() { return frontmatter.get("description"); }
    }
    public record Summary(String name, String description, String uri,
                          String digest, long size, String audience) {}
    public record Detail(Summary skill, String content) {}

    private final Map<String, Detail> details;
    private final List<Entry> entries;
    private final List<Summary> summaries;

    public McpSkillCatalog() {
        try {
            String index = read(INDEX);
            Map<String, Detail> loaded = new LinkedHashMap<>();
            List<Entry> skillEntries = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (String line : index.lines().toList()) {
                String name = line.trim();
                if (name.isEmpty() || name.startsWith("#")) continue;
                if (!NAME.matcher(name).matches() || !seen.add(name)) {
                    throw new IllegalStateException("Invalid or duplicate skill in " + INDEX + ": " + name);
                }
                byte[] bytes = readBytes("mcp-skills/" + name + "/SKILL.md");
                if (bytes.length > MAX_SKILL_BYTES) {
                    throw new IllegalStateException("Skill exceeds the bounded distribution limit: " + name);
                }
                String content = new String(bytes, StandardCharsets.UTF_8);
                Map<String, String> frontmatter = frontmatter(content);
                if (!name.equals(frontmatter.get("name"))) {
                    throw new IllegalStateException("SKILL.md name does not match its directory: " + name);
                }
                String uri = "skill://" + name + "/SKILL.md";
                String digest = digest(bytes);
                Summary summary = new Summary(name, frontmatter.get("description"), uri, digest,
                        bytes.length, name.startsWith("kafkaexplorer-") ? "ENGINEERING" : "KAFKA");
                loaded.put(name, new Detail(summary, content));
                skillEntries.add(new Entry(uri, frontmatter,
                        List.of(new File(uri, digest, bytes.length))));
            }
            if (loaded.isEmpty()) throw new IllegalStateException("No skills in " + INDEX);
            details = Map.copyOf(loaded);
            entries = List.copyOf(skillEntries);
            summaries = List.copyOf(loaded.values().stream().map(Detail::skill).toList());
        } catch (IOException e) {
            throw new IllegalStateException("Cannot load packaged Agent Skills", e);
        }
    }

    public List<Summary> summaries() { return summaries; }
    public List<Entry> entries() { return entries; }
    public Optional<Detail> find(String name) { return Optional.ofNullable(details.get(name)); }

    private static Map<String, String> frontmatter(String markdown) {
        String[] lines = markdown.split("\\R", -1);
        if (lines.length < 4 || !"---".equals(lines[0])) {
            throw new IllegalStateException("SKILL.md must begin with YAML frontmatter");
        }
        Map<String, String> properties = new LinkedHashMap<>();
        boolean closed = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if ("---".equals(line)) { closed = true; break; }
            int separator = line.indexOf(": ");
            if (separator < 1 || line.startsWith(" ") || line.startsWith("#")) {
                throw new IllegalStateException("Unsupported SKILL.md frontmatter syntax: " + line);
            }
            String key = line.substring(0, separator);
            String value = line.substring(separator + 2).trim();
            if (!Set.of("name", "description").contains(key) || value.isBlank()
                    || properties.putIfAbsent(key, value) != null) {
                throw new IllegalStateException("Invalid or duplicate SKILL.md field: " + key);
            }
        }
        if (!closed || !properties.containsKey("name") || !properties.containsKey("description")) {
            throw new IllegalStateException("SKILL.md requires name and description");
        }
        return Map.copyOf(properties);
    }

    private static String read(String path) throws IOException {
        return new String(readBytes(path), StandardCharsets.UTF_8);
    }

    private static byte[] readBytes(String path) throws IOException {
        ClassPathResource resource = new ClassPathResource(path);
        if (!resource.exists()) throw new IOException("Missing packaged resource: " + path);
        try (var input = resource.getInputStream()) {
            return input.readAllBytes();
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
