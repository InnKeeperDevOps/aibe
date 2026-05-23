package com.sitemanager.model.enums;

/**
 * How often the global spend total is treated as "reset to zero" when
 * comparing against the configured cap. DAILY and MONTHLY use calendar
 * boundaries (UTC midnight / first of the month); NEVER keeps a running
 * total since the system started.
 */
public enum CostResetPeriod {
    DAILY,
    MONTHLY,
    NEVER
}
