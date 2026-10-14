package com.example.actionlens.ui;

import android.graphics.Color;
import android.graphics.Paint;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.actionlens.R;
import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.TaskPriority;
import com.example.actionlens.data.TaskStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for displaying Action Cards.
 */
public class ActionCardAdapter extends RecyclerView.Adapter<ActionCardAdapter.TaskViewHolder> {

    public interface OnTaskActionListener {
        void onToggleComplete(ActionTask task);
        void onDelete(ActionTask task);
    }

    private final List<ActionTask> taskList = new ArrayList<>();
    private final OnTaskActionListener listener;

    public ActionCardAdapter(OnTaskActionListener listener) {
        this.listener = listener;
    }

    public void setTasks(List<ActionTask> newTasks) {
        this.taskList.clear();
        if (newTasks != null) {
            this.taskList.addAll(newTasks);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public TaskViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_action_card, parent, false);
        return new TaskViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull TaskViewHolder holder, int position) {
        ActionTask task = taskList.get(position);
        holder.bind(task, listener);
    }

    @Override
    public int getItemCount() {
        return taskList.size();
    }

    public static class TaskViewHolder extends RecyclerView.ViewHolder {

        private final TextView tvActionVerb;
        private final TextView tvSourceBadge;
        private final TextView tvPriority;
        private final ImageView btnDelete;
        private final TextView tvTitle;
        private final TextView tvDeadline;
        private final TextView tvLocation;
        private final CheckBox cbComplete;
        private final TextView tvStatus;

        public TaskViewHolder(@NonNull View itemView) {
            super(itemView);
            tvActionVerb = itemView.findViewById(R.id.tvActionVerbChip);
            tvSourceBadge = itemView.findViewById(R.id.tvSourceBadge);
            tvPriority = itemView.findViewById(R.id.tvPriorityBadge);
            btnDelete = itemView.findViewById(R.id.btnDeleteTask);
            tvTitle = itemView.findViewById(R.id.tvTaskTitle);
            tvDeadline = itemView.findViewById(R.id.tvDeadline);
            tvLocation = itemView.findViewById(R.id.tvLocation);
            cbComplete = itemView.findViewById(R.id.cbComplete);
            tvStatus = itemView.findViewById(R.id.tvStatusBadge);
        }

        public void bind(ActionTask task, OnTaskActionListener listener) {
            // Title
            tvTitle.setText(task.getTitle());

            // Action Verb
            if (task.getActionVerb() != null && !task.getActionVerb().isEmpty()) {
                tvActionVerb.setVisibility(View.VISIBLE);
                tvActionVerb.setText(task.getActionVerb().toUpperCase());
            } else {
                tvActionVerb.setVisibility(View.GONE);
            }

            // Source Attribution
            if (task.getSource() != null && !task.getSource().isEmpty()) {
                tvSourceBadge.setVisibility(View.VISIBLE);
                tvSourceBadge.setText("via " + task.getSource());
            } else {
                tvSourceBadge.setVisibility(View.GONE);
            }

            // Priority Badge
            TaskPriority priority = task.getPriority();
            tvPriority.setText(priority.getLabel().toUpperCase());
            if (priority == TaskPriority.URGENT) {
                tvPriority.setTextColor(Color.parseColor("#DC2626"));
                tvPriority.setBackgroundColor(Color.parseColor("#FEE2E2"));
            } else if (priority == TaskPriority.HIGH) {
                tvPriority.setTextColor(Color.parseColor("#D97706"));
                tvPriority.setBackgroundColor(Color.parseColor("#FEF3C7"));
            } else if (priority == TaskPriority.LOW) {
                tvPriority.setTextColor(Color.parseColor("#64748B"));
                tvPriority.setBackgroundColor(Color.parseColor("#F1F5F9"));
            } else {
                tvPriority.setTextColor(Color.parseColor("#2563EB"));
                tvPriority.setBackgroundColor(Color.parseColor("#DBEAFE"));
            }

            // Deadline
            if (task.getDeadlineFormatted() != null && !task.getDeadlineFormatted().isEmpty()) {
                tvDeadline.setText("📅 " + task.getDeadlineFormatted());
            } else {
                tvDeadline.setText("📅 No deadline");
            }

            // Location
            if (task.getLocation() != null && !task.getLocation().isEmpty()) {
                tvLocation.setVisibility(View.VISIBLE);
                tvLocation.setText("📍 " + task.getLocation());
            } else {
                tvLocation.setVisibility(View.GONE);
            }

            // Status and Completed Checkbox
            boolean isCompleted = task.getStatus() == TaskStatus.COMPLETED;
            cbComplete.setOnCheckedChangeListener(null);
            cbComplete.setChecked(isCompleted);

            if (isCompleted) {
                tvTitle.setPaintFlags(tvTitle.getPaintFlags() | Paint.STRIKE_THRU_TEXT_FLAG);
                tvTitle.setAlpha(0.6f);
                tvStatus.setText("COMPLETED");
                tvStatus.setTextColor(Color.parseColor("#16A34A"));
                tvStatus.setBackgroundColor(Color.parseColor("#DCFCE7"));
            } else {
                tvTitle.setPaintFlags(tvTitle.getPaintFlags() & (~Paint.STRIKE_THRU_TEXT_FLAG));
                tvTitle.setAlpha(1.0f);
                tvStatus.setText("ACTIVE");
                tvStatus.setTextColor(Color.parseColor("#64748B"));
                tvStatus.setBackgroundColor(Color.parseColor("#F1F5F9"));
            }

            cbComplete.setOnCheckedChangeListener((buttonView, isChecked) -> {
                if (listener != null) {
                    listener.onToggleComplete(task);
                }
            });

            btnDelete.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onDelete(task);
                }
            });
        }
    }
}
