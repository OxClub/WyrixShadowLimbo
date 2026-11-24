package com.wyrix.shadowlimbo

import android.content.Context
import org.json.JSONObject

data class SolidBlock(val x: Float, val y: Float, val w: Float, val h: Float)
data class PushCrate(var x: Float, var y: Float, val w: Float, val h: Float, var vx: Float = 0f, var vy: Float = 0f)
data class FloorSwitch(val x: Float, val y: Float, val w: Float, val h: Float, val gateIndex: Int, var pressed: Boolean = false)
data class IronGate(val x: Float, val y: Float, val w: Float, val baseH: Float, var currentH: Float = baseH)
data class HazardObj(var x: Float, val y: Float, val w: Float, val h: Float, val type: String, var vx: Float, val minX: Float, val maxX: Float)
data class StoryCue(val x: Float, val text: String, var triggered: Boolean = false)

data class ChapterSpec(
    val id: Int,
    val title: String,
    val subtitle: String,
    val skyTop: Int,
    val skyBottom: Int,
    val fogAlpha: Float,
    val bgTheme: String,
    val audioTrack: String,
    val spawnX: Float,
    val spawnY: Float,
    val goalX: Float,
    val goalY: Float,
    val storyCues: List<StoryCue>,
    val solids: List<SolidBlock>,
    val crates: List<PushCrate>,
    val switches: List<FloorSwitch>,
    val gates: List<IronGate>,
    val hazards: List<HazardObj>
)

object ChapterRepository {
    fun loadAllChapters(context: Context): List<ChapterSpec> {
        val jsonStr = context.assets.open("chapters/story_chapters.json").bufferedReader().use { it.readText() }
        val root = JSONObject(jsonStr)
        val arr = root.getJSONArray("chapters")
        val list = mutableListOf<ChapterSpec>()

        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val spawn = obj.getJSONArray("spawn")
            val goal = obj.getJSONArray("goal")

            val cues = mutableListOf<StoryCue>()
            val cArr = obj.getJSONArray("storyTriggers")
            for (j in 0 until cArr.length()) {
                val c = cArr.getJSONObject(j)
                cues.add(StoryCue(c.getDouble("x").toFloat(), c.getString("text")))
            }

            val solids = mutableListOf<SolidBlock>()
            val sArr = obj.getJSONArray("solids")
            for (j in 0 until sArr.length()) {
                val s = sArr.getJSONArray(j)
                solids.add(SolidBlock(s.getDouble(0).toFloat(), s.getDouble(1).toFloat(), s.getDouble(2).toFloat(), s.getDouble(3).toFloat()))
            }

            val crates = mutableListOf<PushCrate>()
            val crArr = obj.getJSONArray("crates")
            for (j in 0 until crArr.length()) {
                val cr = crArr.getJSONArray(j)
                crates.add(PushCrate(cr.getDouble(0).toFloat(), cr.getDouble(1).toFloat(), cr.getDouble(2).toFloat(), cr.getDouble(3).toFloat()))
            }

            val switches = mutableListOf<FloorSwitch>()
            val swArr = obj.getJSONArray("switches")
            for (j in 0 until swArr.length()) {
                val sw = swArr.getJSONObject(j)
                switches.add(FloorSwitch(sw.getDouble("x").toFloat(), sw.getDouble("y").toFloat(), sw.getDouble("w").toFloat(), sw.getDouble("h").toFloat(), sw.getInt("gateIndex")))
            }

            val gates = mutableListOf<IronGate>()
            val gArr = obj.getJSONArray("gates")
            for (j in 0 until gArr.length()) {
                val g = gArr.getJSONObject(j)
                val h = g.getDouble("h").toFloat()
                gates.add(IronGate(g.getDouble("x").toFloat(), g.getDouble("y").toFloat(), g.getDouble("w").toFloat(), h, h))
            }

            val hazards = mutableListOf<HazardObj>()
            val hArr = obj.getJSONArray("hazards")
            for (j in 0 until hArr.length()) {
                val h = hArr.getJSONObject(j)
                hazards.add(
                    HazardObj(
                        h.getDouble("x").toFloat(),
                        h.getDouble("y").toFloat(),
                        h.getDouble("w").toFloat(),
                        h.getDouble("h").toFloat(),
                        h.getString("type"),
                        h.getDouble("vx").toFloat(),
                        h.getDouble("minX").toFloat(),
                        h.getDouble("maxX").toFloat()
                    )
                )
            }

            list.add(
                ChapterSpec(
                    id = obj.getInt("id"),
                    title = obj.getString("title"),
                    subtitle = obj.getString("subtitle"),
                    skyTop = android.graphics.Color.parseColor(obj.getString("skyTop")),
                    skyBottom = android.graphics.Color.parseColor(obj.getString("skyBottom")),
                    fogAlpha = obj.getDouble("fogAlpha").toFloat(),
                    bgTheme = obj.getString("bgTheme"),
                    audioTrack = obj.getString("audioTrack"),
                    spawnX = spawn.getDouble(0).toFloat(),
                    spawnY = spawn.getDouble(1).toFloat(),
                    goalX = goal.getDouble(0).toFloat(),
                    goalY = goal.getDouble(1).toFloat(),
                    storyCues = cues,
                    solids = solids,
                    crates = crates,
                    switches = switches,
                    gates = gates,
                    hazards = hazards
                )
            )
        }
        return list
    }
}
