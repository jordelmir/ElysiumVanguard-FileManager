package com.elysium.vanguard.core.palette

import android.content.SharedPreferences
import java.util.concurrent.ConcurrentHashMap

/**
 * PHASE 10.8 — Test fake of [SharedPreferences] backed by an
 * in-memory [ConcurrentHashMap].
 *
 * Used by [PaletteStoreTest] and [PaletteManagerTest] so the
 * tests don't need a real Android Context. The class implements
 * only the surface the store uses (getString, getStringSet,
 * edit); the rest of the interface is stubbed.
 */
internal class InMemorySharedPreferences : SharedPreferences {
    private val data = ConcurrentHashMap<String, Any>()

    override fun getAll(): MutableMap<String, *> = data
    override fun getString(key: String?, defValue: String?): String? =
        data[key] as? String ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        @Suppress("UNCHECKED_CAST") (data[key] as? MutableSet<String>) ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Int) ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Long) ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Float) ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = (data[key] as? Boolean) ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = InMemoryEditor(data)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
}

private class InMemoryEditor(
    private val data: ConcurrentHashMap<String, Any>
) : SharedPreferences.Editor {
    private val pending = mutableMapOf<String, Any?>()
    private val removals = mutableSetOf<String>()

    override fun putString(key: String, value: String?): SharedPreferences.Editor = apply { pending[key] = value }
    override fun putStringSet(key: String, values: MutableSet<String>?): SharedPreferences.Editor = apply { pending[key] = values }
    override fun putInt(key: String, value: Int): SharedPreferences.Editor = apply { pending[key] = value }
    override fun putLong(key: String, value: Long): SharedPreferences.Editor = apply { pending[key] = value }
    override fun putFloat(key: String, value: Float): SharedPreferences.Editor = apply { pending[key] = value }
    override fun putBoolean(key: String, value: Boolean): SharedPreferences.Editor = apply { pending[key] = value }
    override fun remove(key: String): SharedPreferences.Editor = apply { removals += key }
    override fun clear(): SharedPreferences.Editor = apply { removals += data.keys }
    override fun commit(): Boolean { apply(); return true }
    override fun apply() {
        removals.forEach { data.remove(it) }
        pending.forEach { (k, v) ->
            if (v == null) data.remove(k) else data[k] = v
        }
    }
}
