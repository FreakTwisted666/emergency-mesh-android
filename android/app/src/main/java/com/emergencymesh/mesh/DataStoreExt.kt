package com.emergencymesh.mesh

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// Extension property for Context to get DataStore
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "meshr_settings")

// Extension function to get DataStore by name
fun Context.getDataStore(name: String): DataStore<Preferences> {
    return when (name) {
        "meshr_settings" -> this.dataStore
        else -> throw IllegalArgumentException("Unknown DataStore name: $name")
    }
}
