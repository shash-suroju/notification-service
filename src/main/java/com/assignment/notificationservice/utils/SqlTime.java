package com.assignment.notificationservice.utils;

import java.sql.Timestamp;
import java.time.Instant;

/** Instant ↔ JDBC bridging for native queries. */
public final class SqlTime {

    private SqlTime() {
    }

    /** Null-safe {@link Timestamp#from}; the driver sends it with its UTC offset, so the instant is preserved. */
    public static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
