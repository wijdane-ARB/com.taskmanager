package com.taskmanager.core.controller;

import com.taskmanager.core.dto.request.RescheduleTaskRequest;
import com.taskmanager.core.dto.response.ApiResponse;
import com.taskmanager.core.dto.response.TaskResponse;
import com.taskmanager.core.service.TaskService;
import com.taskmanager.core.util.CalendarRange;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

/**
 * Calendar endpoints.
 *
 * GET /api/v1/calendar?teamId=...&view=WEEK&date=2026-10-24&tz=Africa/Casablanca
 *   view  DAY | WEEK | MONTH (weeks start Monday), computed in tz
 *   date  any day inside the wanted period (default: today in tz)
 *   tz    IANA zone id; falls back to the X-Timezone header, then UTC
 *   mine  true = only tasks assigned to the caller
 * or an explicit window:
 *   ...&from=2026-10-01T00:00:00Z&to=2026-11-01T00:00:00Z   (max 62 days)
 *
 * A task appears at its scheduledTime; if it has none, at its dueDate.
 * Tasks with neither are not calendar items. CANCELLED tasks are excluded.
 */
@RestController
@RequestMapping("/api/v1")
public class CalendarController {

    private final TaskService taskService;

    public CalendarController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/calendar")
    public ResponseEntity<ApiResponse<List<TaskResponse>>> getCalendar(
            @AuthenticationPrincipal UUID currentUserId,
            @RequestParam UUID teamId,
            @RequestParam(required = false) CalendarRange.View view,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String tz,
            @RequestHeader(value = "X-Timezone", required = false) String tzHeader,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "false") boolean mine
    ) {
        Instant start;
        Instant end;

        if (from != null || to != null) {
            if (from == null || to == null) {
                throw new IllegalArgumentException("Provide both 'from' and 'to', or use 'view'.");
            }
            start = from;
            end = to;
        } else {
            if (view == null) {
                throw new IllegalArgumentException("Provide 'view' (DAY, WEEK, MONTH) or 'from' and 'to'.");
            }
            ZoneId zone = resolveZone(tz != null ? tz : tzHeader);
            LocalDate anchor = date != null ? date : LocalDate.now(zone);
            CalendarRange range = CalendarRange.of(view, anchor, zone);
            start = range.from();
            end = range.to();
        }

        return ResponseEntity.ok(ApiResponse.ok(
                taskService.getCalendarTasks(currentUserId, teamId, start, end, mine)));
    }

    @PatchMapping("/tasks/{taskId}/reschedule")
    public ResponseEntity<ApiResponse<TaskResponse>> reschedule(
            @AuthenticationPrincipal UUID currentUserId,
            @PathVariable UUID taskId,
            @RequestBody RescheduleTaskRequest request
    ) {
        return ResponseEntity.ok(ApiResponse.ok(
                "Task rescheduled", taskService.rescheduleTask(currentUserId, taskId, request)));
    }

    private static ZoneId resolveZone(String id) {
        if (id == null || id.isBlank()) return ZoneOffset.UTC;
        try {
            return ZoneId.of(id.trim());
        } catch (Exception e) {
            throw new IllegalArgumentException("Unknown time zone: " + id);
        }
    }
}