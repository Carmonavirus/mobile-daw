package com.mobiledaw.web

import android.content.SharedPreferences

private fun lsGet(key: String): String? = js("{ try { return localStorage.getItem(key); } catch (e) { return null; } }")
private fun lsSet(key: String, value: String): Unit = js("{ try { localStorage.setItem(key, value); } catch (e) {} }")

/** SharedPreferences kept in localStorage as one JSON-ish map per name. */
object Prefs {
    private val cache = HashMap<String, WebPrefs>()
    fun get(name: String): SharedPreferences = cache.getOrPut(name) { WebPrefs(name) }

    class WebPrefs(private val name: String) : SharedPreferences {
        private val values = HashMap<String, String>()

        init {
            lsGet("prefs:$name")?.split('\u0001')?.forEach { kv ->
                val i = kv.indexOf('\u0002')
                if (i > 0) values[kv.substring(0, i)] = kv.substring(i + 1)
            }
        }

        private fun save() = lsSet("prefs:$name", values.entries.joinToString("\u0001") { it.key + "\u0002" + it.value })

        override fun getBoolean(key: String, def: Boolean) = values[key]?.toBooleanStrictOrNull() ?: def
        override fun getInt(key: String, def: Int) = values[key]?.toIntOrNull() ?: def
        override fun getLong(key: String, def: Long) = values[key]?.toLongOrNull() ?: def
        override fun getFloat(key: String, def: Float) = values[key]?.toFloatOrNull() ?: def
        override fun getString(key: String, def: String?) = values[key] ?: def
        override fun getStringSet(key: String, def: Set<String>?): Set<String>? =
            values[key]?.let { if (it.isEmpty()) emptySet() else it.split('\u0003').toSet() } ?: def
        override fun contains(key: String) = values.containsKey(key)
        override val all: Map<String, *> get() = values.toMap()

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = HashMap<String, String?>()
            var clear = false
            override fun putBoolean(key: String, v: Boolean) = apply { changes[key] = v.toString() }
            override fun putInt(key: String, v: Int) = apply { changes[key] = v.toString() }
            override fun putLong(key: String, v: Long) = apply { changes[key] = v.toString() }
            override fun putFloat(key: String, v: Float) = apply { changes[key] = v.toString() }
            override fun putString(key: String, v: String?) = apply { changes[key] = v }
            override fun putStringSet(key: String, v: Set<String>?) = apply { changes[key] = v?.joinToString("\u0003") }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { clear = true }
            override fun apply() { commit() }
            override fun commit(): Boolean {
                if (clear) values.clear()
                for ((k, v) in changes) if (v == null) values.remove(k) else values[k] = v
                save()
                return true
            }
        }
    }
}
