package com.wyrix.shadowlimbo

import android.content.Context
import android.graphics.*
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

class LimboGameView(context: Context) : SurfaceView(context), SurfaceHolder.Callback, Runnable {
    private var gameThread: Thread? = null
    @Volatile private var running = false

    private enum class GameState { MENU, CHAPTER_SELECT, PLAYING, PAUSED, ENDING }
    private var state = GameState.MENU

    private val soundManager = SoundManager(context)
    private val prefs = context.getSharedPreferences("wyrix_limbo_save", Context.MODE_PRIVATE)
    private var unlockedChapter = prefs.getInt("unlocked_chapter", 0)
    private var currentChapterIdx = 0
    private var chapter: ChapterSpec = ChapterRepository.loadAllChapters(context)[0]

    private val skyDitheredBmp: Bitmap? = loadBitmap("art/cinematic_sky_dithered.png")
    private val bgFarBmp: Bitmap? = loadBitmap("art/parallax_far_woods.png")
    private val bgMidBmp: Bitmap? = loadBitmap("art/parallax_mid_ruins.png")
    private val fgTreesBmp: Bitmap? = loadBitmap("art/parallax_fg_trees.png")
    private val titleArtBmp: Bitmap? = loadBitmap("art/title_cover.png")

    private var playerX = 120f
    private var playerY = 460f
    private var playerVx = 0f
    private var playerVy = 0f
    private var onGround = false
    private var facingRight = true
    private var isPushing = false
    private var animTime = 0f
    private var stepTimer = 0f
    private var cameraX = 0f
    private var deathFlash = 0f

    private var activeStoryText = ""
    private var storyTimer = 0f
    private var chapterBannerTimer = 0f

    private var btnLeft = false
    private var btnRight = false
    private var btnJump = false

    private val vW = 1280f
    private val vH = 720f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val terrainPath = Path()

    private val dustParticles = FloatArray(180) { i ->
        when (i % 3) {
            0 -> Random.nextFloat() * vW
            1 -> Random.nextFloat() * vH
            else -> 1.2f + Random.nextFloat() * 2.5f
        }
    }

    init {
        holder.addCallback(this)
        isFocusable = true
        soundManager.playChapterMusic("audio/ch1_whispering_woods.wav")
    }

    private fun loadBitmap(path: String): Bitmap? {
        return try {
            context.assets.open(path).use { BitmapFactory.decodeStream(it) }
        } catch (_: Exception) {
            null
        }
    }

    private fun startChapter(index: Int) {
        val all = ChapterRepository.loadAllChapters(context)
        currentChapterIdx = index.coerceIn(0, all.size - 1)
        chapter = all[currentChapterIdx]
        playerX = chapter.spawnX
        playerY = chapter.spawnY
        playerVx = 0f
        playerVy = 0f
        cameraX = (playerX - 360f).coerceAtLeast(-100f)
        chapterBannerTimer = 4.0f
        activeStoryText = chapter.subtitle
        storyTimer = 5.5f
        soundManager.playChapterMusic(chapter.audioTrack)
        state = GameState.PLAYING
    }

    override fun surfaceCreated(holder: SurfaceHolder) { resume() }
    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
    override fun surfaceDestroyed(holder: SurfaceHolder) { pause() }

    fun resume() {
        if (running) return
        running = true
        soundManager.resumeMusic()
        gameThread = Thread(this).apply { start() }
    }

    fun pause() {
        running = false
        soundManager.pauseMusic()
        try { gameThread?.join(400) } catch (_: Exception) {}
        gameThread = null
    }

    override fun run() {
        var lastTime = System.nanoTime()
        while (running) {
            val now = System.nanoTime()
            val dt = ((now - lastTime) / 1_000_000_000f).coerceIn(0.001f, 0.033f)
            lastTime = now

            if (state == GameState.PLAYING) updateGame(dt) else animTime += dt

            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    canvas.save()
                    canvas.scale(width / vW, height / vH)
                    renderScene(canvas)
                    canvas.restore()
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }
            }
        }
    }

    private fun updateGame(dt: Float) {
        animTime += dt
        if (chapterBannerTimer > 0f) chapterBannerTimer -= dt
        if (storyTimer > 0f) storyTimer -= dt
        if (deathFlash > 0f) deathFlash = (deathFlash - dt * 2.5f).coerceAtLeast(0f)

        val moveSpeed = 295f
        playerVx = when {
            btnLeft -> { facingRight = false; -moveSpeed }
            btnRight -> { facingRight = true; moveSpeed }
            else -> 0f
        }

        if (btnJump && onGround) {
            playerVy = -645f
            onGround = false
            soundManager.playJump()
        }

        isPushing = false
        playerVy += 1580f * dt
        playerX += playerVx * dt
        resolveHorizontal()

        playerY += playerVy * dt
        onGround = false
        resolveVertical()

        if (onGround && abs(playerVx) > 10f) {
            stepTimer += dt
            if (stepTimer >= 0.30f) {
                stepTimer = 0f
                soundManager.playStep()
            }
        }

        for (c in chapter.crates) {
            c.vy += 1580f * dt
            c.x += c.vx * dt
            c.vx *= 0.80f
            c.y += c.vy * dt
            for (s in chapter.solids) {
                if (overlaps(c.x, c.y, c.w, c.h, s.x, s.y, s.w, s.h) && c.vy > 0f) {
                    c.y = s.y - c.h
                    c.vy = 0f
                }
            }
        }

        for (sw in chapter.switches) {
            var active = overlaps(playerX, playerY, 34f, 68f, sw.x, sw.y - 6f, sw.w, sw.h + 6f)
            if (!active) {
                for (c in chapter.crates) {
                    if (overlaps(c.x, c.y, c.w, c.h, sw.x, sw.y - 6f, sw.w, sw.h + 6f)) {
                        active = true
                        break
                    }
                }
            }
            if (active && !sw.pressed) soundManager.playSwitch()
            sw.pressed = active
            if (sw.gateIndex in chapter.gates.indices) {
                val gate = chapter.gates[sw.gateIndex]
                val targetH = if (active) 0f else gate.baseH
                gate.currentH += (targetH - gate.currentH) * 6f * dt
            }
        }

        for (h in chapter.hazards) {
            if (h.type == "saw") {
                h.x += h.vx * dt
                if (h.x < h.minX || h.x > h.maxX) h.vx = -h.vx
            }
            if (overlaps(playerX + 6f, playerY + 6f, 22f, 56f, h.x + 7f, h.y + 7f, h.w - 14f, h.h - 14f)) {
                triggerDeath()
                return
            }
        }

        for (cue in chapter.storyCues) {
            if (!cue.triggered && abs(playerX - cue.x) < 95f) {
                cue.triggered = true
                activeStoryText = cue.text
                storyTimer = 5.5f
            }
        }

        if (playerY > 820f) {
            triggerDeath()
            return
        }

        if (overlaps(playerX, playerY, 34f, 68f, chapter.goalX, chapter.goalY, 70f, 130f)) {
            soundManager.playGoal()
            if (currentChapterIdx + 1 < 8) {
                unlockedChapter = maxOf(unlockedChapter, currentChapterIdx + 1)
                prefs.edit().putInt("unlocked_chapter", unlockedChapter).apply()
                startChapter(currentChapterIdx + 1)
            } else {
                state = GameState.ENDING
            }
            return
        }

        cameraX += ((playerX - 420f) - cameraX) * 6.5f * dt
        if (cameraX < -120f) cameraX = -120f
    }

    private fun resolveHorizontal() {
        val pw = 34f
        val ph = 68f
        for (s in chapter.solids) {
            if (overlaps(playerX, playerY, pw, ph, s.x, s.y, s.w, s.h)) {
                if (playerVx > 0f) playerX = s.x - pw
                else if (playerVx < 0f) playerX = s.x + s.w
            }
        }
        for (g in chapter.gates) {
            if (g.currentH > 12f && overlaps(playerX, playerY, pw, ph, g.x, g.y, g.w, g.currentH)) {
                if (playerVx > 0f) playerX = g.x - pw
                else if (playerVx < 0f) playerX = g.x + g.w
            }
        }
        for (c in chapter.crates) {
            if (overlaps(playerX, playerY, pw, ph, c.x, c.y, c.w, c.h)) {
                isPushing = true
                if (playerVx > 0f) {
                    c.vx = 175f
                    playerX = c.x - pw
                } else if (playerVx < 0f) {
                    c.vx = -175f
                    playerX = c.x + c.w
                }
            }
        }
    }

    private fun resolveVertical() {
        val pw = 34f
        val ph = 68f
        for (s in chapter.solids) {
            if (overlaps(playerX, playerY, pw, ph, s.x, s.y, s.w, s.h)) {
                if (playerVy > 0f) {
                    playerY = s.y - ph
                    playerVy = 0f
                    onGround = true
                } else if (playerVy < 0f) {
                    playerY = s.y + s.h
                    playerVy = 0f
                }
            }
        }
        for (c in chapter.crates) {
            if (overlaps(playerX, playerY, pw, ph, c.x, c.y, c.w, c.h)) {
                if (playerVy > 0f) {
                    playerY = c.y - ph
                    playerVy = 0f
                    onGround = true
                }
            }
        }
    }

    private fun triggerDeath() {
        deathFlash = 1.0f
        soundManager.playDeath()
        startChapter(currentChapterIdx)
    }

    private fun renderScene(canvas: Canvas) {
        when (state) {
            GameState.MENU -> renderMenu(canvas)
            GameState.CHAPTER_SELECT -> renderChapterSelect(canvas)
            GameState.PLAYING -> renderGameplay(canvas)
            GameState.PAUSED -> {
                renderGameplay(canvas)
                renderPauseOverlay(canvas)
            }
            GameState.ENDING -> renderEnding(canvas)
        }
    }

    private fun renderMenu(canvas: Canvas) {
        if (titleArtBmp != null) {
            canvas.drawBitmap(titleArtBmp, null, RectF(0f, 0f, vW, vH), paint)
        } else {
            canvas.drawColor(Color.rgb(18, 20, 24))
        }

        paint.shader = RadialGradient(vW / 2f, vH / 2f, 780f, Color.TRANSPARENT, Color.argb(200, 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, vW, vH, paint)
        paint.shader = null

        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textPaint.textSize = 74f
        canvas.drawText("SHADOW LIMBO", vW / 2f, 195f, textPaint)

        textPaint.textSize = 24f
        textPaint.color = Color.rgb(200, 208, 220)
        canvas.drawText("THE LOST SISTER  •  A WYRIX ORIGINAL", vW / 2f, 242f, textPaint)

        drawMenuButton(canvas, vW / 2f - 180f, 330f, 360f, 72f, if (unlockedChapter > 0) "CONTINUE JOURNEY" else "START JOURNEY")
        drawMenuButton(canvas, vW / 2f - 180f, 428f, 360f, 72f, "SELECT CHAPTER (${unlockedChapter + 1}/8)")
        drawMenuButton(canvas, vW / 2f - 180f, 526f, 360f, 64f, if (soundManager.isMuted) "AUDIO: MUTED" else "AUDIO: ON")
    }

    private fun renderChapterSelect(canvas: Canvas) {
        canvas.drawColor(Color.rgb(14, 16, 20))
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 44f
        canvas.drawText("SELECT STORY CHAPTER", vW / 2f, 92f, textPaint)

        val all = ChapterRepository.loadAllChapters(context)
        for (i in all.indices) {
            val col = i % 2
            val row = i / 2
            val bx = 130f + col * 520f
            val by = 152f + row * 115f
            val locked = i > unlockedChapter
            val label = if (locked) "LOCKED  •  CHAPTER ${i + 1}" else all[i].title
            drawMenuButton(canvas, bx, by, 490f, 84f, label, dimmed = locked)
        }

        drawMenuButton(canvas, 40f, 35f, 160f, 55f, "← BACK")
    }

    private fun renderGameplay(canvas: Canvas) {
        if (skyDitheredBmp != null) {
            canvas.drawBitmap(skyDitheredBmp, null, RectF(0f, 0f, vW, vH), paint)
        } else {
            canvas.drawColor(Color.rgb(65, 70, 78))
        }

        paint.color = chapter.skyBottom
        paint.alpha = 70
        canvas.drawRect(0f, 0f, vW, vH, paint)
        paint.alpha = 255

        drawTiledParallax(canvas, bgFarBmp, 0.14f, 220)
        drawTiledParallax(canvas, bgMidBmp, 0.36f, 245)

        for (s in chapter.solids) {
            drawOrganicTerrain(canvas, s.x - cameraX, s.y, s.w, s.h)
        }

        for (sw in chapter.switches) {
            paint.color = if (sw.pressed) Color.WHITE else Color.rgb(110, 118, 128)
            val offset = if (sw.pressed) 6f else 0f
            canvas.drawRect(sw.x - cameraX, sw.y + offset, sw.x - cameraX + sw.w, sw.y + sw.h, paint)
        }
        for (g in chapter.gates) {
            if (g.currentH > 2f) {
                val gx = g.x - cameraX
                paint.color = Color.rgb(16, 18, 22)
                canvas.drawRect(gx, g.y, gx + g.w, g.y + g.currentH, paint)
                paint.color = Color.rgb(70, 76, 86)
                canvas.drawRect(gx + 3f, g.y, gx + 6f, g.y + g.currentH, paint)
            }
        }

        for (c in chapter.crates) {
            val cx = c.x - cameraX
            paint.color = Color.rgb(18, 20, 24)
            canvas.drawRect(cx, c.y, cx + c.w, c.y + c.h, paint)
            paint.color = Color.rgb(68, 74, 84)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f
            canvas.drawRect(cx + 3f, c.y + 3f, cx + c.w - 3f, c.y + c.h - 3f, paint)
            canvas.drawLine(cx + 4f, c.y + 4f, cx + c.w - 4f, c.y + c.h - 4f, paint)
            canvas.drawLine(cx + c.w - 4f, c.y + 4f, cx + 4f, c.y + c.h - 4f, paint)
            paint.style = Paint.Style.FILL
        }

        for (h in chapter.hazards) {
            val hx = h.x - cameraX
            if (h.type == "saw") {
                canvas.save()
                canvas.translate(hx + h.w / 2f, h.y + h.h / 2f)
                canvas.rotate(animTime * 460f)
                paint.color = Color.rgb(10, 11, 14)
                canvas.drawCircle(0f, 0f, h.w * 0.44f, paint)
                for (t in 0 until 14) {
                    canvas.rotate(360f / 14f)
                    canvas.drawRect(-3.5f, -h.w * 0.54f, 3.5f, -h.w * 0.34f, paint)
                }
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f
                paint.color = Color.rgb(145, 152, 165)
                canvas.drawCircle(0f, 0f, h.w * 0.38f, paint)
                paint.style = Paint.Style.FILL
                canvas.drawCircle(0f, 0f, 6f, paint)
                canvas.restore()
            } else {
                paint.color = Color.rgb(8, 9, 12)
                canvas.drawRect(hx, h.y + 15f, hx + h.w, h.y + h.h, paint)
                for (tx in 0..3) {
                    val bx = hx + 4f + tx * 10f
                    canvas.drawRect(bx, h.y + 5f, bx + 5f, h.y + 16f, paint)
                }
            }
        }

        val gx = chapter.goalX - cameraX
        val pulse = 0.55f + 0.25f * sin(animTime * 4f)
        paint.shader = LinearGradient(gx, chapter.goalY - 120f, gx, chapter.goalY + 130f, Color.TRANSPARENT, Color.argb((pulse * 195).toInt(), 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawRect(gx - 15f, chapter.goalY - 120f, gx + 85f, chapter.goalY + 130f, paint)
        paint.shader = null

        if (currentChapterIdx == 7) {
            drawLimboCharacter(canvas, gx + 25f, chapter.goalY + 62f, facingRight = false, swing = 0f, pushing = false, isSister = true)
        }

        val swing = if (abs(playerVx) > 10f && onGround) sin(animTime * 19f) * 9.5f else 0f
        drawLimboCharacter(canvas, playerX - cameraX, playerY, facingRight, swing, isPushing, isSister = false)

        drawTiledParallax(canvas, fgTreesBmp, 1.35f, 235)

        paint.color = Color.argb(120, 235, 240, 248)
        for (i in dustParticles.indices step 3) {
            dustParticles[i] = (dustParticles[i] - 0.8f + vW) % vW
            dustParticles[i + 1] = (dustParticles[i + 1] + (if (chapter.bgTheme == "rainy_city") 14f else 0.4f)) % vH
            if (chapter.bgTheme == "rainy_city") {
                canvas.drawLine(dustParticles[i], dustParticles[i + 1], dustParticles[i] - 4f, dustParticles[i + 1] + 16f, paint)
            } else {
                canvas.drawCircle(dustParticles[i], dustParticles[i + 1], dustParticles[i + 2], paint)
            }
        }

        paint.shader = RadialGradient(vW / 2f, vH / 2f, 790f, Color.TRANSPARENT, Color.argb((chapter.fogAlpha * 240).toInt(), 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, vW, vH, paint)
        paint.shader = null

        if (chapterBannerTimer > 0f) {
            val alpha = (chapterBannerTimer.coerceIn(0f, 1f) * 255).toInt()
            textPaint.color = Color.argb(alpha, 255, 255, 255)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 36f
            canvas.drawText(chapter.title.uppercase(), vW / 2f, 110f, textPaint)
        }

        if (storyTimer > 0f && activeStoryText.isNotEmpty()) {
            val alpha = (storyTimer.coerceIn(0f, 1f) * 245).toInt()
            paint.color = Color.argb((alpha * 0.65f).toInt(), 6, 8, 12)
            canvas.drawRoundRect(RectF(210f, 140f, vW - 210f, 198f), 14f, 14f, paint)
            textPaint.color = Color.argb(alpha, 245, 248, 255)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 22f
            canvas.drawText(activeStoryText, vW / 2f, 176f, textPaint)
        }

        drawGameplayControls(canvas)
    }

    private fun drawOrganicTerrain(canvas: Canvas, x: Float, y: Float, w: Float, h: Float) {
        if (x + w < -60f || x > vW + 60f) return
        paint.color = Color.rgb(4, 5, 7)

        terrainPath.reset()
        terrainPath.moveTo(x, y + h)
        terrainPath.lineTo(x, y + 4f)
        var cx = x
        while (cx < x + w) {
            val wave = sin((cx + x) * 0.045f) * 3.5f + cos((cx + x) * 0.11f) * 2f
            terrainPath.lineTo(cx, y + wave)
            cx += 18f
        }
        terrainPath.lineTo(x + w, y + 4f)
        terrainPath.lineTo(x + w, y + h)
        terrainPath.close()
        canvas.drawPath(terrainPath, paint)

        paint.strokeWidth = 2f
        var gx = x + 8f
        while (gx < x + w - 8f) {
            val gh = 5f + abs(sin(gx * 0.17f)) * 7f
            canvas.drawLine(gx, y + 2f, gx - 3f, y - gh, paint)
            canvas.drawLine(gx + 4f, y + 2f, gx + 7f, y - gh * 0.8f, paint)
            gx += 22f
        }

        if (h < 60f) {
            var vx = x + 24f
            while (vx < x + w - 24f) {
                val vlen = 16f + abs(cos(vx * 0.09f)) * 28f
                canvas.drawLine(vx, y + h - 2f, vx + sin(animTime * 2f + vx) * 4f, y + h + vlen, paint)
                vx += 48f
            }
        }
    }

    private fun drawLimboCharacter(canvas: Canvas, x: Float, y: Float, facingRight: Boolean, swing: Float, pushing: Boolean, isSister: Boolean) {
        val lean = if (pushing) (if (facingRight) 5f else -5f) else (if (abs(playerVx) > 10f && !isSister) (if (facingRight) 2.5f else -2.5f) else 0f)
        paint.color = Color.rgb(4, 5, 7)

        val hx = x + 17f + lean
        val hy = y + 14f
        canvas.drawCircle(hx, hy, 13.5f, paint)
        canvas.drawCircle(hx - 6f, hy - 11f, 5f, paint)
        canvas.drawCircle(hx + 1f, hy - 13f, 5.5f, paint)
        canvas.drawCircle(hx + 7f, hy - 10f, 4.5f, paint)
        if (isSister) canvas.drawCircle(hx + 13f, hy - 2f, 7f, paint)

        canvas.drawRoundRect(RectF(x + 9f + lean * 0.6f, y + 25f, x + 25f + lean * 0.6f, y + 49f), 5f, 5f, paint)

        paint.strokeWidth = 4.5f
        if (pushing) {
            val reachX = if (facingRight) x + 36f else x - 2f
            canvas.drawLine(x + 17f + lean, y + 31f, reachX, y + 34f, paint)
        } else {
            canvas.drawLine(x + 17f + lean, y + 30f, x + 17f - swing * 0.6f, y + 46f, paint)
        }

        canvas.drawLine(x + 13f, y + 48f, x + 11f + swing, y + 68f, paint)
        canvas.drawLine(x + 21f, y + 48f, x + 23f - swing, y + 68f, paint)

        val eyeX = if (facingRight) hx + 3f else hx - 8f
        paint.color = Color.argb(65, 255, 255, 255)
        canvas.drawCircle(eyeX + 2.5f, hy - 1f, 8f, paint)
        paint.color = Color.WHITE
        canvas.drawCircle(eyeX, hy - 1f, 2.4f, paint)
        canvas.drawCircle(eyeX + 5.2f, hy - 1f, 2.4f, paint)
    }

    private fun drawGameplayControls(canvas: Canvas) {
        drawControlCircle(canvas, 110f, 615f, 56f, "◀", btnLeft)
        drawControlCircle(canvas, 250f, 615f, 56f, "▶", btnRight)
        drawControlCircle(canvas, 1165f, 610f, 64f, "▲", btnJump)

        drawMenuButton(canvas, 1150f, 28f, 95f, 46f, "II")
        drawMenuButton(canvas, 1035f, 28f, 95f, 46f, if (soundManager.isMuted) "MUTE" else "SFX")
    }

    private fun drawControlCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, symbol: String, active: Boolean) {
        paint.color = if (active) Color.argb(120, 255, 255, 255) else Color.argb(40, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = Color.argb(115, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = Color.argb(210, 255, 255, 255)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 28f
        canvas.drawText(symbol, cx, cy + 10f, textPaint)
    }

    private fun renderPauseOverlay(canvas: Canvas) {
        paint.color = Color.argb(195, 8, 10, 14)
        canvas.drawRect(0f, 0f, vW, vH, paint)

        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 50f
        canvas.drawText("GAME PAUSED", vW / 2f, 220f, textPaint)

        drawMenuButton(canvas, vW / 2f - 170f, 290f, 340f, 68f, "RESUME")
        drawMenuButton(canvas, vW / 2f - 170f, 385f, 340f, 68f, "RESTART CHAPTER")
        drawMenuButton(canvas, vW / 2f - 170f, 480f, 340f, 68f, "MAIN MENU")
    }

    private fun renderEnding(canvas: Canvas) {
        canvas.drawColor(Color.rgb(220, 226, 235))
        textPaint.color = Color.rgb(15, 18, 24)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 54f
        canvas.drawText("REUNITED IN THE LIGHT", vW / 2f, 240f, textPaint)

        textPaint.textSize = 26f
        canvas.drawText("You guided the lost boy through all 8 chapters and rescued Aria.", vW / 2f, 310f, textPaint)
        canvas.drawText("Thank you for playing Shadow Limbo — Developed by WYRIX", vW / 2f, 360f, textPaint)

        drawMenuButton(canvas, vW / 2f - 170f, 450f, 340f, 70f, "RETURN TO MENU")
    }

    private fun drawMenuButton(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, dimmed: Boolean = false) {
        paint.color = if (dimmed) Color.argb(70, 30, 34, 40) else Color.argb(150, 16, 18, 24)
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.color = if (dimmed) Color.argb(80, 150, 160, 175) else Color.argb(175, 220, 228, 240)
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = if (dimmed) Color.argb(120, 200, 205, 215) else Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 23f
        canvas.drawText(label, x + w / 2f, y + h / 2f + 8f, textPaint)
    }

    private fun drawTiledParallax(canvas: Canvas, bmp: Bitmap?, factor: Float, alpha: Int) {
        if (bmp == null) return
        paint.alpha = alpha
        val tileW = vW * 1.35f
        val offset = -(((cameraX * factor) % tileW + tileW) % tileW)
        canvas.drawBitmap(bmp, null, RectF(offset, 0f, offset + tileW, vH), paint)
        canvas.drawBitmap(bmp, null, RectF(offset + tileW, 0f, offset + tileW * 2f, vH), paint)
        paint.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sx = vW / width.toFloat().coerceAtLeast(1f)
        val sy = vH / height.toFloat().coerceAtLeast(1f)

        if (state != GameState.PLAYING) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                handleMenuTouch(event.x * sx, event.y * sy)
            }
            return true
        }

        var left = false
        var right = false
        var jump = false

        for (i in 0 until event.pointerCount) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) continue
            if (action == MotionEvent.ACTION_POINTER_UP && i == event.actionIndex) continue

            val tx = event.getX(i) * sx
            val ty = event.getY(i) * sy

            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                if (tx in 1150f..1245f && ty in 28f..74f) {
                    state = GameState.PAUSED
                    return true
                }
                if (tx in 1035f..1130f && ty in 28f..74f) {
                    soundManager.toggleMute()
                    return true
                }
            }

            if (dist(tx, ty, 110f, 615f) <= 82f) left = true
            else if (dist(tx, ty, 250f, 615f) <= 82f) right = true
            else if (dist(tx, ty, 1165f, 610f) <= 92f) jump = true
        }

        btnLeft = left
        btnRight = right
        btnJump = jump
        return true
    }

    private fun handleMenuTouch(tx: Float, ty: Float) {
        when (state) {
            GameState.MENU -> {
                if (tx in (vW / 2f - 180f)..(vW / 2f + 180f)) {
                    when (ty) {
                        in 330f..402f -> startChapter(unlockedChapter)
                        in 428f..500f -> state = GameState.CHAPTER_SELECT
                        in 526f..590f -> soundManager.toggleMute()
                    }
                }
            }
            GameState.CHAPTER_SELECT -> {
                if (tx in 40f..200f && ty in 35f..90f) {
                    state = GameState.MENU
                    return
                }
                for (i in 0 until 8) {
                    val col = i % 2
                    val row = i / 2
                    val bx = 130f + col * 520f
                    val by = 152f + row * 115f
                    if (tx in bx..(bx + 490f) && ty in by..(by + 84f) && i <= unlockedChapter) {
                        startChapter(i)
                        return
                    }
                }
            }
            GameState.PAUSED -> {
                if (tx in (vW / 2f - 170f)..(vW / 2f + 170f)) {
                    when (ty) {
                        in 290f..358f -> state = GameState.PLAYING
                        in 385f..453f -> startChapter(currentChapterIdx)
                        in 480f..548f -> state = GameState.MENU
                    }
                }
            }
            GameState.ENDING -> {
                if (tx in (vW / 2f - 170f)..(vW / 2f + 170f) && ty in 450f..520f) {
                    state = GameState.MENU
                }
            }
            else -> {}
        }
    }

    private fun dist(x1: Float, y1: Float, x2: Float, y2: Float): Float {
        val dx = x1 - x2
        val dy = y1 - y2
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    private fun overlaps(ax: Float, ay: Float, aw: Float, ah: Float, bx: Float, by: Float, bw: Float, bh: Float): Boolean {
        return ax < bx + bw && ax + aw > bx && ay < by + bh && ay + ah > by
    }
}
