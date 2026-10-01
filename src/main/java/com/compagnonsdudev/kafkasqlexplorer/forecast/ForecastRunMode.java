// SPDX-License-Identifier: AGPL-3.0-or-later
package com.compagnonsdudev.kafkasqlexplorer.forecast;

/** Controls whether a forecast may affect an operator-visible decision. */
public enum ForecastRunMode {
    SHADOW,
    ACTIVE
}
