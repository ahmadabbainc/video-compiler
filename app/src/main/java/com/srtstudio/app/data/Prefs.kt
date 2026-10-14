package com.srtstudio.app.data

import android.content.Context
import java.io.File

/** Remembers the look-and-feel settings between launches. */
class Prefs(context: Context) {
    private val p = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load(): RenderSettings {
        val d = RenderSettings()
        return RenderSettings(
            size = enumOr(p.getString("size", null), d.size),
            fps = p.getInt("fps", d.fps),
            fontScale = p.getFloat("fontScale", d.fontScale),
            textColor = p.getInt("textColor", d.textColor),
            position = enumOr(p.getString("position", null), d.position),
            style = enumOr(p.getString("style", null), d.style),
            kenBurns = p.getBoolean("kenBurns", d.kenBurns),
            crossfadeMs = p.getInt("crossfadeMs", d.crossfadeMs),
            mapping = enumOr(p.getString("mapping", null), d.mapping),
            palette = p.getInt("palette", d.palette),
        )
    }

    fun save(s: RenderSettings) {
        p.edit()
            .putString("size", s.size.name)
            .putInt("fps", s.fps)
            .putFloat("fontScale", s.fontScale)
            .putInt("textColor", s.textColor)
            .putString("position", s.position.name)
            .putString("style", s.style.name)
            .putBoolean("kenBurns", s.kenBurns)
            .putInt("crossfadeMs", s.crossfadeMs)
            .putString("mapping", s.mapping.name)
            .putInt("palette", s.palette)
            .apply()
    }

    fun fontPath(): String? = p.getString("fontPath", null)?.takeIf { File(it).exists() }

    fun fontName(): String? = p.getString("fontName", null)

    fun saveFont(path: String?, name: String?) {
        p.edit().putString("fontPath", path).putString("fontName", name).apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        runCatching { enumValueOf<T>(name ?: "") }.getOrDefault(default)
}
