// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Kafka Explorer Contributors
package com.yourcompany.kafkasqlexplorer.domain;

public enum AuditStatus {
    RUNNING,
    COMPLETED,
    /**
     * Stopped on request. The report still carries the topics audited before the stop — a
     * cancelled scan of 200 topics out of 2 000 is a partial answer, not no answer — and
     * {@code globalStats.cancelled} marks it so nothing reads it as a full-cluster verdict.
     */
    CANCELLED,
    FAILED
}
