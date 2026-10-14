package com.example.actionlens.data;

import android.app.Application;

import androidx.lifecycle.LiveData;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Repository mediating access to the ActionTask data source.
 * Ensures all Room write operations are safely dispatched on a background thread.
 */
public class TaskRepository {

    public interface OnTaskInsertedCallback {
        void onInserted(long taskId);
    }

    private final ActionTaskDao actionTaskDao;
    private final ExecutorService executorService;

    public TaskRepository(Application application) {
        ActionLensDatabase db = ActionLensDatabase.getInstance(application);
        this.actionTaskDao = db.actionTaskDao();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public TaskRepository(ActionTaskDao actionTaskDao) {
        this.actionTaskDao = actionTaskDao;
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public LiveData<List<ActionTask>> getTasksByStatus(TaskStatus status) {
        return actionTaskDao.getTasksByStatus(status);
    }

    public LiveData<List<ActionTask>> getAllTasks() {
        return actionTaskDao.getAllTasks();
    }

    public void insert(ActionTask task, OnTaskInsertedCallback callback) {
        executorService.execute(() -> {
            long id = actionTaskDao.insert(task);
            if (callback != null) {
                callback.onInserted(id);
            }
        });
    }

    public void update(ActionTask task) {
        executorService.execute(() -> actionTaskDao.update(task));
    }

    public void delete(ActionTask task) {
        executorService.execute(() -> actionTaskDao.delete(task));
    }

    public void markCompleted(ActionTask task) {
        task.setStatus(TaskStatus.COMPLETED);
        task.setUpdatedAt(System.currentTimeMillis());
        update(task);
    }

    public void markDismissed(ActionTask task) {
        task.setStatus(TaskStatus.DISMISSED);
        task.setUpdatedAt(System.currentTimeMillis());
        update(task);
    }

    public void markActive(ActionTask task) {
        task.setStatus(TaskStatus.ACTIVE);
        task.setUpdatedAt(System.currentTimeMillis());
        update(task);
    }

    public int countMatchingTasks(String title, Long deadlineTimestamp) {
        return actionTaskDao.countMatchingTasks(title, deadlineTimestamp);
    }

    public long insertSync(ActionTask task) {
        return actionTaskDao.insert(task);
    }
}
