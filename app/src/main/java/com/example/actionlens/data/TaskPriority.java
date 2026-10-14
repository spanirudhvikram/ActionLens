package com.example.actionlens.data;

/**
 * Priority levels for actionable tasks.
 */
public enum TaskPriority {
    LOW("Low", "#64748B"),
    NORMAL("Normal", "#2563EB"),
    HIGH("High", "#D97706"),
    URGENT("Urgent", "#DC2626");

    private final String label;
    private final String colorHex;

    TaskPriority(String label, String colorHex) {
        this.label = label;
        this.colorHex = colorHex;
    }

    public String getLabel() {
        return label;
    }

    public String getColorHex() {
        return colorHex;
    }
}
