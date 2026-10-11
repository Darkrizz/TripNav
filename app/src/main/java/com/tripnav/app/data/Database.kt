package com.tripnav.app.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "trips")
data class TripEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    val endedAt: Long,
    val distanceM: Double,
    val movingSeconds: Long,
    val maxSpeedKmh: Double,
    /** Track as a precision-5 encoded polyline. */
    val track: String,
    val title: String,
)

@Entity(tableName = "places")
data class PlaceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val address: String,
    val lat: Double,
    val lon: Double,
    val favorite: Boolean,
    val lastUsed: Long,
)

/** Google Maps maneuver bitmaps the rider has taught TripNav, keyed by perceptual hash. */
@Entity(tableName = "learned_icons")
data class LearnedIcon(@PrimaryKey val hash: Long, val podIcon: Int)

@Dao
interface TripDao {
    @Query("SELECT * FROM trips ORDER BY endedAt DESC")
    fun all(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :id")
    suspend fun get(id: Long): TripEntity?

    @Insert
    suspend fun insert(trip: TripEntity): Long

    @Query("DELETE FROM trips WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface PlaceDao {
    @Query("SELECT * FROM places WHERE favorite = 1 ORDER BY name")
    fun favorites(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places ORDER BY lastUsed DESC LIMIT 15")
    fun recents(): Flow<List<PlaceEntity>>

    @Query("SELECT * FROM places WHERE abs(lat - :lat) < 0.0002 AND abs(lon - :lon) < 0.0002 LIMIT 1")
    suspend fun near(lat: Double, lon: Double): PlaceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(place: PlaceEntity): Long

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface LearnedIconDao {
    @Query("SELECT * FROM learned_icons")
    suspend fun all(): List<LearnedIcon>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(icon: LearnedIcon)
}

@Database(entities = [TripEntity::class, PlaceEntity::class, LearnedIcon::class], version = 1, exportSchema = true)
abstract class TripNavDatabase : RoomDatabase() {
    abstract fun trips(): TripDao
    abstract fun places(): PlaceDao
    abstract fun learnedIcons(): LearnedIconDao

    companion object {
        fun create(context: Context) =
            Room.databaseBuilder(context, TripNavDatabase::class.java, "tripnav.db").build()
    }
}
