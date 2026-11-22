package com.murtdah.fit.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "drinks")
data class Drink(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val time: Long,
    val ml: Int,
    val kind: String = "water",
    val kcal: Int = 0,
    val proteinG: Int = 0,
)

@Entity(tableName = "meals")
data class Meal(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val time: Long,
    val name: String,
    val kcal: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val sodiumMg: Int,
    val fiberG: Int = 0,
    val sweet: Boolean = false,
    val flex: Boolean = false,
)

@Entity(tableName = "favourites")
data class Favourite(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val kcal: Int,
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val sodiumMg: Int,
    val fiberG: Int = 0,
    val sweet: Boolean = false,
)

@Entity(tableName = "weights")
data class WeightEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val time: Long,
    val kg: Double,
    val fasted: Boolean = true,
)

@Entity(tableName = "scans")
data class Scan(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val time: Long,
    val weightKg: Double,
    val bodyFatPct: Double,
    val skeletalMusclePct: Double?,
    val muscleMassKg: Double?,
    val visceralFat: Double?,
    val proteinPct: Double?,
    val bodyWaterPct: Double?,
    val bmrKcal: Int?,
    val fatFreeMassKg: Double?,
) {
    val fatMassKg: Double get() = weightKg * bodyFatPct / 100.0
}

@Entity(tableName = "sessions")
data class WorkoutSession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val date: String,
    val dayType: String,
    val startedAt: Long,
    val endedAt: Long? = null,
    val completed: Boolean = false,
    val recovery: Boolean = false,
    val light: String = "GREEN",
    val readinessReasons: String = "",
    val volumeKg: Double = 0.0,
    val bests: Int = 0,
)

@Entity(tableName = "sets")
data class SetEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val date: String,
    val exerciseId: String,
    val stepIndex: Int,
    val reps: Int,
    val rir: Int?,
    val loadKg: Double?,
    val seconds: Int,
    val time: Long,
)

@Entity(tableName = "progress")
data class ProgressRow(
    @PrimaryKey val exerciseId: String,
    val rung: String,
    val loadKg: Double?,
    val targetReps: Int,
    val stalledAtTop: Int,
    val lastSessionId: Long = 0,
)

@Entity(tableName = "days")
data class DayLog(
    @PrimaryKey val date: String,
    val postureDone: Boolean = false,
    val watchBurnKcal: Int? = null,
    val steps: Int = 0,
    val stepSource: String = "NONE",
    val recoveryDay: Boolean = false,
    val skipReason: String? = null,
    val notificationsFired: Int = 0,
)

@Entity(tableName = "supplements")
data class Supplement(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val halalChecked: Boolean = false,
    val lastTakenDate: String? = null,
)

@Dao
interface FitDao {
    @Insert suspend fun insertDrink(d: Drink): Long
    @Query("DELETE FROM drinks WHERE id = :id") suspend fun deleteDrink(id: Long)
    @Query("SELECT * FROM drinks WHERE date = :date ORDER BY time") fun drinks(date: String): Flow<List<Drink>>
    @Query("SELECT * FROM drinks WHERE date = :date ORDER BY time") suspend fun drinksNow(date: String): List<Drink>
    @Query("SELECT * FROM drinks WHERE date BETWEEN :from AND :to") suspend fun drinksBetween(from: String, to: String): List<Drink>
    @Query("SELECT COALESCE(SUM(ml),0) FROM drinks WHERE time >= :since") suspend fun drinkMlSince(since: Long): Int

    @Insert suspend fun insertMeal(m: Meal): Long
    @Query("DELETE FROM meals WHERE id = :id") suspend fun deleteMeal(id: Long)
    @Query("SELECT * FROM meals WHERE date = :date ORDER BY time") fun meals(date: String): Flow<List<Meal>>
    @Query("SELECT * FROM meals WHERE date = :date ORDER BY time") suspend fun mealsNow(date: String): List<Meal>
    @Query("SELECT * FROM meals WHERE date BETWEEN :from AND :to ORDER BY time") suspend fun mealsBetween(from: String, to: String): List<Meal>
    @Query("SELECT * FROM meals ORDER BY time DESC LIMIT 1") suspend fun lastMeal(): Meal?

    @Insert suspend fun insertFavourite(f: Favourite): Long
    @Query("DELETE FROM favourites WHERE id = :id") suspend fun deleteFavourite(id: Long)
    @Query("SELECT * FROM favourites ORDER BY name") fun favourites(): Flow<List<Favourite>>

    @Insert suspend fun insertWeight(w: WeightEntry): Long
    @Query("DELETE FROM weights WHERE id = :id") suspend fun deleteWeight(id: Long)
    @Query("SELECT * FROM weights ORDER BY time") fun weights(): Flow<List<WeightEntry>>
    @Query("SELECT * FROM weights WHERE date BETWEEN :from AND :to AND fasted = 1 ORDER BY time") suspend fun fastedBetween(from: String, to: String): List<WeightEntry>

    @Insert suspend fun insertScan(s: Scan): Long
    @Query("DELETE FROM scans WHERE id = :id") suspend fun deleteScan(id: Long)
    @Query("SELECT * FROM scans ORDER BY time") fun scans(): Flow<List<Scan>>
    @Query("SELECT * FROM scans ORDER BY time DESC LIMIT 2") suspend fun lastTwoScans(): List<Scan>

    @Insert suspend fun insertSession(s: WorkoutSession): Long
    @Query("UPDATE sessions SET endedAt = :ended, completed = :completed, volumeKg = :volume, bests = :bests WHERE id = :id")
    suspend fun finishSession(id: Long, ended: Long, completed: Boolean, volume: Double, bests: Int)
    @Query("SELECT * FROM sessions WHERE date = :date ORDER BY startedAt") fun sessions(date: String): Flow<List<WorkoutSession>>
    @Query("SELECT * FROM sessions WHERE date = :date") suspend fun sessionsNow(date: String): List<WorkoutSession>
    @Query("SELECT * FROM sessions WHERE date BETWEEN :from AND :to ORDER BY startedAt") suspend fun sessionsBetween(from: String, to: String): List<WorkoutSession>
    @Query("SELECT * FROM sessions ORDER BY startedAt") suspend fun allSessions(): List<WorkoutSession>

    @Insert suspend fun insertSet(s: SetEntry): Long
    @Query("SELECT * FROM sets WHERE sessionId = :sessionId ORDER BY stepIndex") suspend fun setsFor(sessionId: Long): List<SetEntry>
    @Query("SELECT * FROM sets WHERE exerciseId = :exerciseId AND sessionId != :exclude AND rir IS NOT NULL ORDER BY time DESC LIMIT 6")
    suspend fun recentSets(exerciseId: String, exclude: Long): List<SetEntry>
    @Query("SELECT MAX(reps * COALESCE(loadKg, 1.0)) FROM sets WHERE exerciseId = :exerciseId AND sessionId != :exclude AND rir IS NOT NULL")
    suspend fun bestScore(exerciseId: String, exclude: Long): Double?

    @Upsert suspend fun upsertProgress(p: ProgressRow)
    @Query("SELECT * FROM progress") suspend fun allProgress(): List<ProgressRow>

    @Upsert suspend fun upsertDay(d: DayLog)
    @Query("SELECT * FROM days WHERE date = :date") suspend fun dayNow(date: String): DayLog?
    @Query("SELECT * FROM days WHERE date = :date") fun day(date: String): Flow<DayLog?>
    @Query("SELECT * FROM days WHERE date BETWEEN :from AND :to") suspend fun daysBetween(from: String, to: String): List<DayLog>

    @Insert suspend fun insertSupplement(s: Supplement): Long
    @Upsert suspend fun upsertSupplement(s: Supplement)
    @Query("DELETE FROM supplements WHERE id = :id") suspend fun deleteSupplement(id: Long)
    @Query("SELECT * FROM supplements ORDER BY name") fun supplements(): Flow<List<Supplement>>

    @Query("SELECT * FROM drinks") suspend fun allDrinks(): List<Drink>
    @Query("SELECT * FROM meals") suspend fun allMeals(): List<Meal>
    @Query("SELECT * FROM favourites") suspend fun allFavourites(): List<Favourite>
    @Query("SELECT * FROM weights") suspend fun allWeights(): List<WeightEntry>
    @Query("SELECT * FROM scans") suspend fun allScans(): List<Scan>
    @Query("SELECT * FROM sets") suspend fun allSets(): List<SetEntry>
    @Query("SELECT * FROM days") suspend fun allDays(): List<DayLog>
    @Query("SELECT * FROM supplements") suspend fun allSupplements(): List<Supplement>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDrinks(x: List<Drink>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putMeals(x: List<Meal>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putFavourites(x: List<Favourite>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putWeights(x: List<WeightEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putScans(x: List<Scan>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSessions(x: List<WorkoutSession>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSets(x: List<SetEntry>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putProgress(x: List<ProgressRow>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putDays(x: List<DayLog>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun putSupplements(x: List<Supplement>)
}

@Database(
    entities = [Drink::class, Meal::class, Favourite::class, WeightEntry::class, Scan::class, WorkoutSession::class, SetEntry::class, ProgressRow::class, DayLog::class, Supplement::class],
    version = 1,
    exportSchema = true,
)
abstract class FitDatabase : RoomDatabase() {
    abstract fun dao(): FitDao
}
