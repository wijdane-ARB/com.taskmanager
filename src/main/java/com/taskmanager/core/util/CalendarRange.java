package com.taskmanager.core.util;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.TemporalAdjusters;

/**
 * Half-open time window [from, to) for a calendar view, computed in the
 * user's own time zone so "Monday 00:00" means Monday for them, not for UTC.
 * Weeks start on Monday. DST days are handled by atStartOfDay(zone), so a
 * day can be 23 or 25 hours long.
 */
public record CalendarRange(Instant from, Instant to) {

    public enum View { DAY, WEEK, MONTH }

    public static CalendarRange of(View view, LocalDate anchor, ZoneId zone) {
        LocalDate start;
        LocalDate endExclusive;
        switch (view) {
            case DAY -> {
                start = anchor;
                endExclusive = anchor.plusDays(1);
            }
            case WEEK -> {
                start = anchor.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
                endExclusive = start.plusWeeks(1);
            }
            case MONTH -> {
                start = anchor.withDayOfMonth(1);
                endExclusive = start.plusMonths(1);
            }
            default -> throw new IllegalArgumentException("Unsupported view: " + view);
        }
        return new CalendarRange(
                start.atStartOfDay(zone).toInstant(),
                endExclusive.atStartOfDay(zone).toInstant());
    }
}