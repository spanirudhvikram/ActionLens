package com.example.actionlens.data;

import androidx.annotation.NonNull;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * Structured Action Task record stored locally in Room.
 *
 * Privacy Invariant:
 * Stored records contain ONLY extracted actionable entities (title, deadline, action verb, etc.).
 * Original raw message/announcement text is NEVER persisted.
 */
@Entity(tableName = "action_tasks")
public class ActionTask {

    @PrimaryKey(autoGenerate = true)
    private long id;

    @NonNull
    private String title;

    private String actionVerb;

    private Long deadlineTimestamp;

    private String deadlineFormatted;

    private boolean hasExplicitTime;

    @NonNull
    private TaskPriority priority;

    @NonNull
    private TaskStatus status;

    private String location;

    private String source;

    private long createdAt;

    private long updatedAt;

    public ActionTask(@NonNull String title,
                      String actionVerb,
                      Long deadlineTimestamp,
                      String deadlineFormatted,
                      boolean hasExplicitTime,
                      @NonNull TaskPriority priority,
                      @NonNull TaskStatus status,
                      String location,
                      String source,
                      long createdAt,
                      long updatedAt) {
        this.title = title;
        this.actionVerb = actionVerb;
        this.deadlineTimestamp = deadlineTimestamp;
        this.deadlineFormatted = deadlineFormatted;
        this.hasExplicitTime = hasExplicitTime;
        this.priority = priority != null ? priority : TaskPriority.NORMAL;
        this.status = status != null ? status : TaskStatus.ACTIVE;
        this.location = location;
        this.source = source != null ? source : "Manual Input";
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
        this.updatedAt = updatedAt > 0 ? updatedAt : System.currentTimeMillis();
    }

    public long getId() {
        return id;
    }

    public void setId(long id) {
        this.id = id;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    public void setTitle(@NonNull String title) {
        this.title = title;
    }

    public String getActionVerb() {
        return actionVerb;
    }

    public void setActionVerb(String actionVerb) {
        this.actionVerb = actionVerb;
    }

    public Long getDeadlineTimestamp() {
        return deadlineTimestamp;
    }

    public void setDeadlineTimestamp(Long deadlineTimestamp) {
        this.deadlineTimestamp = deadlineTimestamp;
    }

    public String getDeadlineFormatted() {
        return deadlineFormatted;
    }

    public void setDeadlineFormatted(String deadlineFormatted) {
        this.deadlineFormatted = deadlineFormatted;
    }

    public boolean isHasExplicitTime() {
        return hasExplicitTime;
    }

    public void setHasExplicitTime(boolean hasExplicitTime) {
        this.hasExplicitTime = hasExplicitTime;
    }

    @NonNull
    public TaskPriority getPriority() {
        return priority;
    }

    public void setPriority(@NonNull TaskPriority priority) {
        this.priority = priority;
    }

    @NonNull
    public TaskStatus getStatus() {
        return status;
    }

    public void setStatus(@NonNull TaskStatus status) {
        this.status = status;
    }

    public String getLocation() {
        return location;
    }

    public void setLocation(String location) {
        this.location = location;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }
}
