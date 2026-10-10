package com.radwan.raadpharmacy.data

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteDriver
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.AndroidSQLiteDriver

/** Keep WAL concurrency, with a disk sync at each committed financial transaction. */
internal class DurableLedgerDriver(private val driver: SQLiteDriver = AndroidSQLiteDriver()) : SQLiteDriver by driver {
    override fun open(fileName: String): SQLiteConnection = DurableConnection(driver.open(fileName))

    private class DurableConnection(private val connection: SQLiteConnection) : SQLiteConnection by connection {
        override fun prepare(sql: String): SQLiteStatement {
            // Room configures every pooled connection to NORMAL after opening it. Promote
            // that one pragma here, so even a later writer/reopened connection uses FULL.
            val durable = if (sql.trim().trimEnd(';').matches(Regex("PRAGMA\\s+synchronous\\s*=\\s*NORMAL", RegexOption.IGNORE_CASE)))
                "PRAGMA synchronous = FULL" else sql
            return connection.prepare(durable)
        }
    }
}
