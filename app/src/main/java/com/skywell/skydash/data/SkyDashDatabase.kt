package com.skywell.skydash.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class SkyDashDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    override fun onCreate(db: SQLiteDatabase) {
        // 1. Trips table
        db.execSQL("""
            CREATE TABLE trips (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                start_time INTEGER,
                end_time INTEGER,
                distance REAL,
                duration INTEGER,
                energy_used REAL,
                avg_consumption REAL,
                avg_speed REAL,
                cost REAL
            )
        """)

        // 2. Telemetry table (for Black Box graphs)
        db.execSQL("""
            CREATE TABLE telemetry (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                trip_id INTEGER,
                timestamp INTEGER,
                soc INTEGER,
                speed REAL,
                altitude REAL,
                motor_temp REAL,
                cell_diff_mv INTEGER,
                FOREIGN KEY(trip_id) REFERENCES trips(id) ON DELETE CASCADE
            )
        """)

        // 3. Charge Sessions table
        db.execSQL("""
            CREATE TABLE charge_sessions (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                timestamp INTEGER,
                type TEXT,
                start_soc INTEGER,
                end_soc INTEGER,
                energy_added REAL,
                cost REAL
            )
        """)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS telemetry")
        db.execSQL("DROP TABLE IF EXISTS trips")
        db.execSQL("DROP TABLE IF EXISTS charge_sessions")
        onCreate(db)
    }

    companion object {
        private const val DATABASE_NAME = "skydash.db"
        private const val DATABASE_VERSION = 1
    }
}
