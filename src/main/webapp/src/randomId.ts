// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors

/*
 * Identifier generation that works on the origins this application is actually served from.
 *
 * `crypto.randomUUID()` exists only in a *secure context* — HTTPS, or `http://localhost`.
 * This app ships with no authentication and is documented as an internal tool, so the
 * ordinary deployment is plain HTTP on a hostname: `http://kafkadev:9964`. There
 * `crypto.randomUUID` is not merely unreliable, it is `undefined`, and calling it throws
 * `TypeError: crypto.randomUUID is not a function`.
 *
 * That failure was invisible to every local test, because `localhost` is a secure context
 * by exception — the one origin a developer uses is the one origin where the bug cannot
 * reproduce.
 *
 * `crypto.getRandomValues()` carries no such restriction, so a v4 UUID is built from it.
 * The last resort exists only so this function is *total*: a caller must never have to
 * defend against it throwing, which was the entire defect.
 */

/** Formats 16 bytes as a v4 UUID, setting the version and variant bits. */
function formatUuid(bytes: Uint8Array): string {
  bytes[6] = (bytes[6] & 0x0f) | 0x40; // version 4
  bytes[8] = (bytes[8] & 0x3f) | 0x80; // variant 10xx
  const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

/**
 * A UUID-shaped identifier, on any origin.
 *
 * The shape matters beyond aesthetics: the backend accepts a client-supplied query id only
 * when it matches `[A-Za-z0-9_-]{8,64}` (`FlinkSqlService.resolveQueryId`) and silently
 * mints its own otherwise — which would leave the cancel endpoint unable to find the job,
 * exactly the symptom this function exists to prevent. Every branch below returns 36
 * characters drawn from that alphabet.
 */
export function randomId(source: Crypto | undefined = globalThis.crypto): string {
  // Optional call, not a truthiness test: the property is absent on an insecure origin.
  try {
    const uuid = source?.randomUUID?.();
    if (uuid) return uuid;
  } catch {
    // Some environments expose the property and throw on use; fall through.
  }

  try {
    if (source?.getRandomValues) {
      return formatUuid(source.getRandomValues(new Uint8Array(16)));
    }
  } catch {
    // Fall through to the arithmetic fallback.
  }

  // Not cryptographically strong, and does not need to be: this identifies a query within
  // one process so it can be cancelled. Being *total* is the requirement here.
  const bytes = new Uint8Array(16);
  for (let i = 0; i < bytes.length; i += 1) bytes[i] = Math.floor(Math.random() * 256);
  return formatUuid(bytes);
}
