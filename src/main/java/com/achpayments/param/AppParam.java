package com.achpayments.param;

import java.time.LocalDateTime;

/** One row of the AppParam table. */
public record AppParam(
        String systemName,
        String paramName,
        String paramDesc,
        String paramRawType,
        String paramRawValue,
        LocalDateTime lastModTm,
        String lastModBy) {
}
