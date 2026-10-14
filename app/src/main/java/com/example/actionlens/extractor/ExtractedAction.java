package com.example.actionlens.extractor;

import androidx.annotation.NonNull;

import com.example.actionlens.data.TaskPriority;

/**
 * Value object holding the structured action entities extracted
 * from sanitized text.
 */
public class ExtractedAction {

    @NonNull
    private final String title;
    private final String actionVerb;
    private final Long deadlineTimestamp;
    private final String deadlineFormatted;
    private final boolean hasExplicitTime;
    private final boolean isDateAmbiguous;
    private final String ambiguousDateReason;
    @NonNull
    private final TaskPriority priority;
    private final String location;
    private final String source;

    public ExtractedAction(
            @NonNull String title,
            String actionVerb,
            Long deadlineTimestamp,
            String deadlineFormatted,
            boolean hasExplicitTime,
            boolean isDateAmbiguous,
            String ambiguousDateReason,
            @NonNull TaskPriority priority,
            String location,
            String source) {
        this.title = title;
        this.actionVerb = actionVerb;
        this.deadlineTimestamp = deadlineTimestamp;
        this.deadlineFormatted = deadlineFormatted;
        this.hasExplicitTime = hasExplicitTime;
        this.isDateAmbiguous = isDateAmbiguous;
        this.ambiguousDateReason = ambiguousDateReason;
        this.priority = priority != null ? priority : TaskPriority.NORMAL;
        this.location = location;
        this.source = source != null ? source : "Manual Input";
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    public String getActionVerb() {
        return actionVerb;
    }

    public Long getDeadlineTimestamp() {
        return deadlineTimestamp;
    }

    public String getDeadlineFormatted() {
        return deadlineFormatted;
    }

    public boolean hasDeadline() {
        return deadlineTimestamp != null;
    }

    public boolean isHasExplicitTime() {
        return hasExplicitTime;
    }

    public boolean isDateAmbiguous() {
        return isDateAmbiguous;
    }

    public String getAmbiguousDateReason() {
        return ambiguousDateReason;
    }

    @NonNull
    public TaskPriority getPriority() {
        return priority;
    }

    public String getLocation() {
        return location;
    }

    public String getSource() {
        return source;
    }
}
