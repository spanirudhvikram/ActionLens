package com.example.actionlens.data;

import android.content.Context;

import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

/**
 * Local SQLite database for ActionLens powered by Room.
 * Stores only structured ActionTask records.
 */
@Database(entities = {ActionTask.class}, version = 1, exportSchema = false)
@TypeConverters({Converters.class})
public abstract class ActionLensDatabase extends RoomDatabase {

    private static final String DATABASE_NAME = "actionlens_database";
    private static volatile ActionLensDatabase INSTANCE;

    public abstract ActionTaskDao actionTaskDao();

    public static ActionLensDatabase getInstance(Context context) {
        if (INSTANCE == null) {
            synchronized (ActionLensDatabase.class) {
                if (INSTANCE == null) {
                    INSTANCE = Room.databaseBuilder(
                            context.getApplicationContext(),
                            ActionLensDatabase.class,
                            DATABASE_NAME
                    ).fallbackToDestructiveMigration().build();
                }
            }
        }
        return INSTANCE;
    }
}
