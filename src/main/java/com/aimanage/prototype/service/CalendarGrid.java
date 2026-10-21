package com.aimanage.prototype.service;

import com.aimanage.prototype.domain.Task;
import java.time.*;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

public final class CalendarGrid {
    private CalendarGrid() {}

    public static List<List<Day>> month(YearMonth month, List<Task> tasks) {
        LocalDate first = month.atDay(1).with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        LocalDate last = month.atEndOfMonth().with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        return range(first, last, month, tasks);
    }

    public static List<List<Day>> week(LocalDate date, List<Task> tasks) {
        LocalDate first = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
        return range(first, first.plusDays(6), YearMonth.from(date), tasks);
    }

    private static List<List<Day>> range(LocalDate first, LocalDate last, YearMonth month, List<Task> tasks) {
        Map<LocalDate, List<Task>> byDate = new HashMap<>();
        for (Task task : tasks) if (task.getDueDate() != null)
            byDate.computeIfAbsent(task.getDueDate(), ignored -> new ArrayList<>()).add(task);
        List<List<Day>> weeks = new ArrayList<>();
        for (LocalDate start = first; !start.isAfter(last); start = start.plusWeeks(1)) {
            List<Day> days = new ArrayList<>();
            for (int offset = 0; offset < 7; offset++) {
                LocalDate date = start.plusDays(offset);
                days.add(new Day(date, YearMonth.from(date).equals(month), byDate.getOrDefault(date, List.of())));
            }
            weeks.add(days);
        }
        return weeks;
    }

    public record Day(LocalDate date, boolean currentMonth, List<Task> tasks) {}
}
