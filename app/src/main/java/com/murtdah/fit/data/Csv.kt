package com.murtdah.fit.data

object Csv {
    fun escape(v: Any?): String {
        val s = v?.toString() ?: ""
        return if (s.any { it == ',' || it == '"' || it == '\n' }) "\"" + s.replace("\"", "\"\"") + "\"" else s
    }

    fun parseLine(line: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                quoted && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> { sb.append('"'); i++ }
                c == '"' -> quoted = !quoted
                c == ',' && !quoted -> { out += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        out += sb.toString()
        return out
    }

    private fun table(name: String, header: List<String>, rows: List<List<Any?>>): String = buildString {
        append("#").append(name).append('\n')
        append(header.joinToString(",")).append('\n')
        rows.forEach { r -> append(r.joinToString(",") { escape(it) }).append('\n') }
    }

    suspend fun export(dao: FitDao): String = buildString {
        append(table("drinks", listOf("id", "date", "time", "ml", "kind", "kcal", "proteinG"), dao.allDrinks().map { listOf(it.id, it.date, it.time, it.ml, it.kind, it.kcal, it.proteinG) }))
        append(table("meals", listOf("id", "date", "time", "name", "kcal", "proteinG", "fatG", "carbsG", "sodiumMg", "fiberG", "sweet", "flex"),
            dao.allMeals().map { listOf(it.id, it.date, it.time, it.name, it.kcal, it.proteinG, it.fatG, it.carbsG, it.sodiumMg, it.fiberG, it.sweet, it.flex) }))
        append(table("favourites", listOf("id", "name", "kcal", "proteinG", "fatG", "carbsG", "sodiumMg", "fiberG", "sweet"),
            dao.allFavourites().map { listOf(it.id, it.name, it.kcal, it.proteinG, it.fatG, it.carbsG, it.sodiumMg, it.fiberG, it.sweet) }))
        append(table("weights", listOf("id", "date", "time", "kg", "fasted"), dao.allWeights().map { listOf(it.id, it.date, it.time, it.kg, it.fasted) }))
        append(table("scans", listOf("id", "date", "time", "weightKg", "bodyFatPct", "skeletalMusclePct", "muscleMassKg", "visceralFat", "proteinPct", "bodyWaterPct", "bmrKcal", "fatFreeMassKg"),
            dao.allScans().map { listOf(it.id, it.date, it.time, it.weightKg, it.bodyFatPct, it.skeletalMusclePct, it.muscleMassKg, it.visceralFat, it.proteinPct, it.bodyWaterPct, it.bmrKcal, it.fatFreeMassKg) }))
        append(table("sessions", listOf("id", "date", "dayType", "startedAt", "endedAt", "completed", "recovery", "light", "readinessReasons", "volumeKg", "bests"),
            dao.allSessions().map { listOf(it.id, it.date, it.dayType, it.startedAt, it.endedAt, it.completed, it.recovery, it.light, it.readinessReasons, it.volumeKg, it.bests) }))
        append(table("sets", listOf("id", "sessionId", "date", "exerciseId", "stepIndex", "reps", "rir", "loadKg", "seconds", "time"),
            dao.allSets().map { listOf(it.id, it.sessionId, it.date, it.exerciseId, it.stepIndex, it.reps, it.rir, it.loadKg, it.seconds, it.time) }))
        append(table("progress", listOf("exerciseId", "rung", "loadKg", "targetReps", "stalledAtTop", "lastSessionId"),
            dao.allProgress().map { listOf(it.exerciseId, it.rung, it.loadKg, it.targetReps, it.stalledAtTop, it.lastSessionId) }))
        append(table("days", listOf("date", "postureDone", "watchBurnKcal", "steps", "stepSource", "recoveryDay", "skipReason", "notificationsFired"),
            dao.allDays().map { listOf(it.date, it.postureDone, it.watchBurnKcal, it.steps, it.stepSource, it.recoveryDay, it.skipReason, it.notificationsFired) }))
        append(table("supplements", listOf("id", "name", "halalChecked", "lastTakenDate"), dao.allSupplements().map { listOf(it.id, it.name, it.halalChecked, it.lastTakenDate) }))
    }

    fun sections(text: String): Map<String, List<Map<String, String>>> {
        val out = mutableMapOf<String, MutableList<Map<String, String>>>()
        var name: String? = null
        var header: List<String>? = null
        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r')
            when {
                line.isBlank() -> Unit
                line.startsWith("#") -> { name = line.drop(1).trim(); header = null; out.getOrPut(name!!) { mutableListOf() } }
                header == null -> header = parseLine(line)
                else -> {
                    val cells = parseLine(line)
                    out[name!!]!! += header!!.indices.associate { header!![it] to cells.getOrElse(it) { "" } }
                }
            }
        }
        return out
    }

    suspend fun import(dao: FitDao, text: String): Int {
        val s = sections(text)
        fun Map<String, String>.l(k: String) = this[k]?.toLongOrNull() ?: 0L
        fun Map<String, String>.i(k: String) = this[k]?.toIntOrNull() ?: 0
        fun Map<String, String>.io(k: String) = this[k]?.toIntOrNull()
        fun Map<String, String>.d(k: String) = this[k]?.toDoubleOrNull() ?: 0.0
        fun Map<String, String>.dO(k: String) = this[k]?.toDoubleOrNull()
        fun Map<String, String>.b(k: String) = this[k] == "true"
        fun Map<String, String>.so(k: String) = this[k]?.ifEmpty { null }
        var n = 0
        s["drinks"]?.let { r -> dao.putDrinks(r.map { Drink(it.l("id"), it["date"]!!, it.l("time"), it.i("ml"), it["kind"] ?: "water", it.i("kcal"), it.i("proteinG")) }); n += r.size }
        s["meals"]?.let { r -> dao.putMeals(r.map { Meal(it.l("id"), it["date"]!!, it.l("time"), it["name"]!!, it.i("kcal"), it.i("proteinG"), it.i("fatG"), it.i("carbsG"), it.i("sodiumMg"), it.i("fiberG"), it.b("sweet"), it.b("flex")) }); n += r.size }
        s["favourites"]?.let { r -> dao.putFavourites(r.map { Favourite(it.l("id"), it["name"]!!, it.i("kcal"), it.i("proteinG"), it.i("fatG"), it.i("carbsG"), it.i("sodiumMg"), it.i("fiberG"), it.b("sweet")) }); n += r.size }
        s["weights"]?.let { r -> dao.putWeights(r.map { WeightEntry(it.l("id"), it["date"]!!, it.l("time"), it.d("kg"), it.b("fasted")) }); n += r.size }
        s["scans"]?.let { r -> dao.putScans(r.map { Scan(it.l("id"), it["date"]!!, it.l("time"), it.d("weightKg"), it.d("bodyFatPct"), it.dO("skeletalMusclePct"), it.dO("muscleMassKg"), it.dO("visceralFat"), it.dO("proteinPct"), it.dO("bodyWaterPct"), it.io("bmrKcal"), it.dO("fatFreeMassKg")) }); n += r.size }
        s["sessions"]?.let { r -> dao.putSessions(r.map { WorkoutSession(it.l("id"), it["date"]!!, it["dayType"]!!, it.l("startedAt"), it["endedAt"]?.toLongOrNull(), it.b("completed"), it.b("recovery"), it["light"] ?: "GREEN", it["readinessReasons"] ?: "", it.d("volumeKg"), it.i("bests")) }); n += r.size }
        s["sets"]?.let { r -> dao.putSets(r.map { SetEntry(it.l("id"), it.l("sessionId"), it["date"]!!, it["exerciseId"]!!, it.i("stepIndex"), it.i("reps"), it.io("rir"), it.dO("loadKg"), it.i("seconds"), it.l("time")) }); n += r.size }
        s["progress"]?.let { r -> dao.putProgress(r.map { ProgressRow(it["exerciseId"]!!, it["rung"] ?: "BASE", it.dO("loadKg"), it.i("targetReps"), it.i("stalledAtTop"), it.l("lastSessionId")) }); n += r.size }
        s["days"]?.let { r -> dao.putDays(r.map { DayLog(it["date"]!!, it.b("postureDone"), it.io("watchBurnKcal"), it.i("steps"), it["stepSource"] ?: "NONE", it.b("recoveryDay"), it.so("skipReason"), it.i("notificationsFired")) }); n += r.size }
        s["supplements"]?.let { r -> dao.putSupplements(r.map { Supplement(it.l("id"), it["name"]!!, it.b("halalChecked"), it.so("lastTakenDate")) }); n += r.size }
        return n
    }
}
