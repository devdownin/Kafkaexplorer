// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

/** Safe dependency states: never carries a remote body, token or submitted observations. */
public final class TimesFmInferenceException extends RuntimeException {
    public enum State { BUSY, UNAVAILABLE, TIMEOUT, INVALID_OUTPUT }
    private final State state;
    public TimesFmInferenceException(State state) { super("TimesFM " + state); this.state = state; }
    public State state() { return state; }
}
