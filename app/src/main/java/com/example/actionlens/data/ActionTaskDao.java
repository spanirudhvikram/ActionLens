package com.example.actionlens.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

/**
 * Data Access Object for ActionTask operations.
 */
@Dao
public interface ActionTaskDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insert(ActionTask task);

    @Update
    void update(ActionTask task);

    @Delete
    void delete(ActionTask task);

    @Query("SELECT * FROM action_tasks WHERE status = :status ORDER BY CASE WHEN deadlineTimestamp IS NULL THEN 1 ELSE 0 END, deadlineTimestamp ASC, createdAt DESC")
    LiveData<List<ActionTask>> getTasksByStatus(TaskStatus status);

    @Query("SELECT * FROM action_tasks ORDER BY createdAt DESC")
    LiveData<List<ActionTask>> getAllTasks();

    @Query("SELECT * FROM action_tasks WHERE status = :status ORDER BY CASE WHEN deadlineTimestamp IS NULL THEN 1 ELSE 0 END, deadlineTimestamp ASC, createdAt DESC")
    List<ActionTask> getTasksByStatusSync(TaskStatus status);

    @Query("SELECT * FROM action_tasks ORDER BY createdAt DESC")
    List<ActionTask> getAllTasksSync();

    @Query("SELECT * FROM action_tasks WHERE id = :id LIMIT 1")
    ActionTask getTaskById(long id);

    @Query("SELECT COUNT(*) FROM action_tasks")
    int getTaskCount();

    @Query("SELECT COUNT(*) FROM action_tasks WHERE title = :title AND status = 'ACTIVE' AND ((deadlineTimestamp IS NULL AND :deadlineTimestamp IS NULL) OR deadlineTimestamp = :deadlineTimestamp)")
    int countMatchingTasks(String title, Long deadlineTimestamp);

    @Query("DELETE FROM action_tasks")
    void deleteAll();
}
