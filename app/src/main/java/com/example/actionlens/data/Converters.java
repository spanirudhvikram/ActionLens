package com.example.actionlens.data;

import androidx.room.TypeConverter;

/**
 * Room type converters for TaskPriority and TaskStatus enums.
 */
public class Converters {

    @TypeConverter
    public static String fromPriority(TaskPriority priority) {
        return priority == null ? TaskPriority.NORMAL.name() : priority.name();
    }

    @TypeConverter
    public static TaskPriority toPriority(String value) {
        if (value == null) {
            return TaskPriority.NORMAL;
        }
        try {
            return TaskPriority.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TaskPriority.NORMAL;
        }
    }

    @TypeConverter
    public static String fromStatus(TaskStatus status) {
        return status == null ? TaskStatus.ACTIVE.name() : status.name();
    }

    @TypeConverter
    public static TaskStatus toStatus(String value) {
        if (value == null) {
            return TaskStatus.ACTIVE;
        }
        try {
            return TaskStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            return TaskStatus.ACTIVE;
        }
    }
}
