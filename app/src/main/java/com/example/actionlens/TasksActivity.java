package com.example.actionlens;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.LiveData;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.actionlens.data.ActionTask;
import com.example.actionlens.data.TaskRepository;
import com.example.actionlens.data.TaskStatus;
import com.example.actionlens.privacy.PrivacyApprovalCallback;
import com.example.actionlens.privacy.PrivacyApprovalDialog;
import com.example.actionlens.privacy.PrivacyAssessment;
import com.example.actionlens.service.ActionLensNotificationListener;
import com.example.actionlens.service.NotificationProcessor;
import com.example.actionlens.ui.ActionCardAdapter;
import com.example.actionlens.ui.AddTaskDialog;
import com.example.actionlens.util.NotificationPermissionHelper;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

import java.util.List;

public class TasksActivity extends AppCompatActivity implements ActionCardAdapter.OnTaskActionListener, NotificationProcessor.ForegroundReviewer {

    private enum FilterMode {
        ACTIVE,
        COMPLETED,
        ALL
    }

    private TaskRepository repository;
    private ActionCardAdapter adapter;
    private RecyclerView rvTasks;
    private LinearLayout llEmptyState;
    private LinearLayout llNotificationBanner;
    private Button btnFilterActive;
    private Button btnFilterCompleted;
    private Button btnFilterAll;

    private FilterMode currentFilter = FilterMode.ACTIVE;
    private LiveData<List<ActionTask>> currentLiveData;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_tasks);

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        repository = new TaskRepository(getApplication());

        initViews();
        setupRecyclerView();
        setupFilters();
        observeTasks();
    }

    private void initViews() {
        ImageView btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish());

        ImageView btnSettings = findViewById(R.id.btnSettings);
        if (btnSettings != null) {
            btnSettings.setOnClickListener(v -> {
                Intent intent = new Intent(TasksActivity.this, PrivacySettingsActivity.class);
                startActivity(intent);
            });
        }

        llNotificationBanner = findViewById(R.id.llNotificationBanner);
        Button btnBannerEnable = findViewById(R.id.btnBannerEnable);
        if (btnBannerEnable != null) {
            btnBannerEnable.setOnClickListener(v -> {
                Intent intent = new Intent(TasksActivity.this, PrivacySettingsActivity.class);
                startActivity(intent);
            });
        }

        rvTasks = findViewById(R.id.rvTasks);
        llEmptyState = findViewById(R.id.llEmptyState);

        btnFilterActive = findViewById(R.id.btnFilterActive);
        btnFilterCompleted = findViewById(R.id.btnFilterCompleted);
        btnFilterAll = findViewById(R.id.btnFilterAll);

        Button btnEmptyStateAdd = findViewById(R.id.btnEmptyStateAdd);
        btnEmptyStateAdd.setOnClickListener(v -> openAddTaskDialog());

        ExtendedFloatingActionButton fabAddTask = findViewById(R.id.fabAddTask);
        fabAddTask.setOnClickListener(v -> openAddTaskDialog());
    }

    private void setupRecyclerView() {
        adapter = new ActionCardAdapter(this);
        rvTasks.setLayoutManager(new LinearLayoutManager(this));
        rvTasks.setAdapter(adapter);
    }

    private void setupFilters() {
        btnFilterActive.setOnClickListener(v -> {
            currentFilter = FilterMode.ACTIVE;
            updateFilterButtons();
            observeTasks();
        });

        btnFilterCompleted.setOnClickListener(v -> {
            currentFilter = FilterMode.COMPLETED;
            updateFilterButtons();
            observeTasks();
        });

        btnFilterAll.setOnClickListener(v -> {
            currentFilter = FilterMode.ALL;
            updateFilterButtons();
            observeTasks();
        });

        updateFilterButtons();
    }

    private void updateFilterButtons() {
        int activeColor = getColor(R.color.primary);
        int inactiveColor = getColor(R.color.text_secondary);

        btnFilterActive.setTextColor(currentFilter == FilterMode.ACTIVE ? activeColor : inactiveColor);
        btnFilterCompleted.setTextColor(currentFilter == FilterMode.COMPLETED ? activeColor : inactiveColor);
        btnFilterAll.setTextColor(currentFilter == FilterMode.ALL ? activeColor : inactiveColor);
    }

    private void observeTasks() {
        if (currentLiveData != null) {
            currentLiveData.removeObservers(this);
        }

        if (currentFilter == FilterMode.ACTIVE) {
            currentLiveData = repository.getTasksByStatus(TaskStatus.ACTIVE);
        } else if (currentFilter == FilterMode.COMPLETED) {
            currentLiveData = repository.getTasksByStatus(TaskStatus.COMPLETED);
        } else {
            currentLiveData = repository.getAllTasks();
        }

        currentLiveData.observe(this, tasks -> {
            adapter.setTasks(tasks);
            if (tasks == null || tasks.isEmpty()) {
                rvTasks.setVisibility(View.GONE);
                llEmptyState.setVisibility(View.VISIBLE);
            } else {
                rvTasks.setVisibility(View.VISIBLE);
                llEmptyState.setVisibility(View.GONE);
            }
        });
    }

    private void openAddTaskDialog() {
        AddTaskDialog dialog = new AddTaskDialog(this, repository, task -> {
            // Task created; LiveData automatically updates list
        });
        dialog.show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean isEnabled = NotificationPermissionHelper.isNotificationListenerEnabled(this);
        if (llNotificationBanner != null) {
            llNotificationBanner.setVisibility(isEnabled ? View.GONE : View.VISIBLE);
        }
        ActionLensNotificationListener.setForegroundReviewer(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        ActionLensNotificationListener.clearForegroundReviewer(this);
    }

    @Override
    public void requestApproval(PrivacyAssessment assessment, Runnable onApproved, Runnable onRejected) {
        runOnUiThread(() -> {
            if (isFinishing() || isDestroyed()) {
                if (onRejected != null) {
                    onRejected.run();
                }
                return;
            }
            PrivacyApprovalDialog dialog = new PrivacyApprovalDialog(this, assessment, new PrivacyApprovalCallback() {
                @Override
                public void onApproved(String sanitizedText) {
                    if (onApproved != null) {
                        onApproved.run();
                    }
                }

                @Override
                public void onRejected() {
                    if (onRejected != null) {
                        onRejected.run();
                    }
                }
            });
            dialog.show();
        });
    }

    @Override
    public void onToggleComplete(ActionTask task) {
        if (task.getStatus() == TaskStatus.COMPLETED) {
            repository.markActive(task);
        } else {
            repository.markCompleted(task);
        }
    }

    @Override
    public void onDelete(ActionTask task) {
        repository.delete(task);
    }
}