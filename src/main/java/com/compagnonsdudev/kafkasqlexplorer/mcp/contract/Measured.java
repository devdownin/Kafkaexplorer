// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.compagnonsdudev.kafkasqlexplorer.mcp.contract;

/**
 * A value that can legitimately fail to be measured, carrying the reason when it does.
 *
 * <p>The type exists to make {@code 0} unwritable by accident. Zero is an assertion — "caught up",
 * "no failures", "nothing waiting" — and the application already learned that lesson on the
 * consumer-lag path ({@code PartitionTimeLag}). Published to a language model, a zero standing in
 * for "could not read" produces a conclusion that is both wrong and confident, and nothing
 * downstream can recover the difference. A bare {@code null} is no better: JSON {@code null} reads
 * as absence, and absence reads as nothing-there.
 *
 * <p><b>The three components are the wire shape, and that is deliberate.</b> The obvious design is
 * two components plus a {@code @JsonSerialize} that flattens them — and it would work in exactly
 * one of the two places this record is serialised. The application's own REST surface runs on
 * Jackson 2 ({@code com.fasterxml.jackson}); the MCP transport shipped by Spring AI runs on
 * Jackson 3 ({@code tools.jackson}). A serializer registered against one mapper is silently absent
 * from the other, and the half that lost it emits {@code {"value":null,"unmeasuredReason":"…"}} —
 * the naked null this record exists to prevent, on the surface an agent reads. Making the shape
 * structural rather than annotated means both mappers, and any future third, produce it by
 * default.
 *
 * <p>Construct through {@link #of} and {@link #unmeasured} only; the canonical constructor refuses
 * an incoherent triple rather than letting a hand-built one drift.
 *
 * @param value    the measurement, null exactly when it could not be taken
 * @param measured whether the measurement happened — redundant with {@code value != null} in Java,
 *                 load-bearing in JSON, where it is what stops a null being read as zero
 * @param reason   why not, in a sentence an operator can act on; null when measured
 */
public record Measured<T>(T value, boolean measured, String reason) {

    public Measured {
        if (measured && (value == null || reason != null)) {
            throw new IllegalArgumentException("a measured value has a value and no reason");
        }
        if (!measured && (value != null || reason == null || reason.isBlank())) {
            throw new IllegalArgumentException("an unmeasured value has no value and must say why");
        }
    }

    public static <T> Measured<T> of(T value) {
        return new Measured<>(value, true, null);
    }

    public static <T> Measured<T> unmeasured(String why) {
        return new Measured<>(null, false, why);
    }

    /**
     * {@code of(value)} when the value is there, {@code unmeasured(why)} when it is not.
     *
     * <p>For the common adapter shape: a service field that is boxed precisely because it may be
     * absent. Written out at each call site it becomes a ternary that is easy to get backwards.
     */
    public static <T> Measured<T> ofNullable(T value, String whyIfAbsent) {
        return value == null ? unmeasured(whyIfAbsent) : of(value);
    }
}
