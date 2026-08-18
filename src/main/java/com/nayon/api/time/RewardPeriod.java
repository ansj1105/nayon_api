package com.nayon.api.time;

import java.time.LocalDate;
import java.time.ZonedDateTime;

public record RewardPeriod(
        String periodType,
        LocalDate periodKey,
        ZonedDateTime startsAt,
        ZonedDateTime endsAt,
        String zoneId) {
}
