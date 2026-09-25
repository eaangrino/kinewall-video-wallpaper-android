package com.eaangrino.kinewall

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Dao
interface WallpaperDao {
    @Query("SELECT * FROM wallpapers ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<WallpaperEntity>>

    @Query("SELECT * FROM wallpapers ORDER BY createdAt DESC")
    suspend fun getAll(): List<WallpaperEntity>

    @Query("SELECT * FROM wallpapers WHERE id = :id LIMIT 1")
    suspend fun getById(id: String): WallpaperEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(wallpaper: WallpaperEntity)

    @Delete
    suspend fun delete(wallpaper: WallpaperEntity)
}

@Database(
    entities = [WallpaperEntity::class],
    version = 1,
    exportSchema = false
)
abstract class KineWallDatabase : RoomDatabase() {
    abstract fun wallpaperDao(): WallpaperDao

    companion object {
        @Volatile
        private var instance: KineWallDatabase? = null

        fun get(context: Context): KineWallDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                KineWallDatabase::class.java,
                "kinewall.db"
            ).build().also { instance = it }
        }
    }
}
