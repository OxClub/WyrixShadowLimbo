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

    // High-Res Hand-Crafted Art Bitmaps loaded from assets/art/
    private val bgFarBmp: Bitmap? = loadBitmap("art/parallax_far_woods.png")
    private val bgMidBmp: Bitmap? = loadBitmap("art/parallax_mid_ruins.png")
    private val titleArtBmp: Bitmap? = loadBitmap("art/title_cover.png")

    // Player State
    private var playerX = 120f
    private var playerY = 460f
    private var playerVx = 0f
    private var playerVy = 0f
    private var onGround = false
    private var facingRight = true
    private var animTime = 0f
    private var stepTimer = 0f
    private var cameraX = 0f
    private var deathFlash = 0f

    // Story Narrative Banner State
    private var activeStoryText = ""
    private var storyTimer = 0f
    private var chapterBannerTimer = 0f

    // Touch Control Flags
    private var btnLeft = false
    private var btnRight = false
    private var btnJump = false

    // Virtual Resolution (1280 x 720)
    private val vW = 1280f
    private val vH = 720f

    // Reusable Paints
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // Rain Particles for Atmospheric Depth
    private val rainDrops = FloatArray(160) { i ->
        if (i % 2 == 0) Random.nextFloat() * vW else Random.nextFloat() * vH
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
        cameraX = (playerX - 350f).coerceAtLeast(-100f)
        chapterBannerTimer = 4.2f
        activeStoryText = chapter.subtitle
        storyTimer = 5.5f
        soundManager.playChapterMusic(chapter.audioTrack)
        state = GameState.PLAYING
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        resume()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        pause()
    }

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

            if (state == GameState.PLAYING) {
                updateGame(dt)
            } else {
                animTime += dt
            }

            val canvas = holder.lockCanvas()
            if (canvas != null) {
                try {
                    val scaleX = width / vW
                    val scaleY = height / vH
                    canvas.save()
                    canvas.scale(scaleX, scaleY)
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

        val moveSpeed = 300f
        playerVx = when {
            btnLeft -> { facingRight = false; -moveSpeed }
            btnRight -> { facingRight = true; moveSpeed }
            else -> 0f
        }

        if (btnJump && onGround) {
            playerVy = -640f
            onGround = false
            soundManager.playJump()
        }

        playerVy += 1580f * dt
        playerX += playerVx * dt
        resolveHorizontal()

        playerY += playerVy * dt
        onGround = false
        resolveVertical()

        // Footsteps Audio
        if (onGround && abs(playerVx) > 10f) {
            stepTimer += dt
            if (stepTimer >= 0.30f) {
                stepTimer = 0f
                soundManager.playStep()
            }
        }

        // Update Pushable Crates
        for (c in chapter.crates) {
            c.vy += 1580f * dt
            c.x += c.vx * dt
            c.vx *= 0.82f
            c.y += c.vy * dt
            for (s in chapter.solids) {
                if (overlaps(c.x, c.y, c.w, c.h, s.x, s.y, s.w, s.h) && c.vy > 0f) {
                    c.y = s.y - c.h
                    c.vy = 0f
                }
            }
        }

        // Check Pressure Switches & Gates
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
            if (active && !sw.pressed) {
                soundManager.playSwitch()
            }
            sw.pressed = active
            if (sw.gateIndex in chapter.gates.indices) {
                val gate = chapter.gates[sw.gateIndex]
                val targetH = if (active) 0f else gate.baseH
                gate.currentH += (targetH - gate.currentH) * 6f * dt
            }
        }

        // Update Moving Sawblades & Hazards
        for (h in chapter.hazards) {
            if (h.type == "saw") {
                h.x += h.vx * dt
                if (h.x < h.minX || h.x > h.maxX) h.vx = -h.vx
            }
            if (overlaps(playerX + 5f, playerY + 5f, 24f, 58f, h.x + 6f, h.y + 6f, h.w - 12f, h.h - 12f)) {
                triggerDeath()
                return
            }
        }

        // Story Narrative Triggers
        for (cue in chapter.storyCues) {
            if (!cue.triggered && abs(playerX - cue.x) < 90f) {
                cue.triggered = true
                activeStoryText = cue.text
                storyTimer = 5.5f
            }
        }

        // Pitfall Check
        if (playerY > 820f) {
            triggerDeath()
            return
        }

        // Goal / Chapter Completion Check
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
                if (playerVx > 0f) {
                    c.vx = 180f
                    playerX = c.x - pw
                } else if (playerVx < 0f) {
                    c.vx = -180f
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

        // Dark Atmospheric Vignette
        paint.shader = RadialGradient(vW / 2f, vH / 2f, 750f, Color.TRANSPARENT, Color.argb(210, 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, vW, vH, paint)
        paint.shader = null

        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)
        textPaint.textSize = 72f
        canvas.drawText("SHADOW LIMBO", vW / 2f, 200f, textPaint)

        textPaint.textSize = 26f
        textPaint.color = Color.rgb(190, 198, 210)
        canvas.drawText("THE LOST SISTER  •  A WYRIX ORIGINAL", vW / 2f, 248f, textPaint)

        // Start Journey Button
        drawMenuButton(canvas, vW / 2f - 180f, 340f, 360f, 74f, if (unlockedChapter > 0) "CONTINUE JOURNEY" else "START JOURNEY")
        // Chapter Select Button
        drawMenuButton(canvas, vW / 2f - 180f, 440f, 360f, 74f, "SELECT CHAPTER (${unlockedChapter + 1}/8)")
        // Sound Toggle Button
        drawMenuButton(canvas, vW / 2f - 180f, 540f, 360f, 64f, if (soundManager.isMuted) "AUDIO: MUTED" else "AUDIO: ON")
    }

    private fun renderChapterSelect(canvas: Canvas) {
        canvas.drawColor(Color.rgb(16, 18, 22))
        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 46f
        canvas.drawText("SELECT STORY CHAPTER", vW / 2f, 95f, textPaint)

        val all = ChapterRepository.loadAllChapters(context)
        for (i in all.indices) {
            val col = i % 2
            val row = i / 2
            val bx = 130f + col * 520f
            val by = 155f + row * 115f
            val locked = i > unlockedChapter
            val label = if (locked) "LOCKED  •  CHAPTER ${i + 1}" else all[i].title
            drawMenuButton(canvas, bx, by, 490f, 86f, label, dimmed = locked)
        }

        drawMenuButton(canvas, 40f, 35f, 160f, 55f, "← BACK")
    }

    private fun renderGameplay(canvas: Canvas) {
        // 1. Sky Gradient
        paint.shader = LinearGradient(0f, 0f, 0f, vH, chapter.skyTop, chapter.skyBottom, Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, vW, vH, paint)
        paint.shader = null

        // 2. Parallax Hand-Drawn Layers
        drawTiledParallax(canvas, bgFarBmp, 0.18f, 180)
        drawTiledParallax(canvas, bgMidBmp, 0.42f, 225)

        // 3. Solids & Ground
        paint.color = Color.rgb(10, 11, 14)
        for (s in chapter.solids) {
            canvas.drawRect(s.x - cameraX, s.y, s.x - cameraX + s.w, s.y + s.h, paint)
        }

        // 4. Pressure Switches & Iron Gates
        for (sw in chapter.switches) {
            paint.color = if (sw.pressed) Color.WHITE else Color.rgb(90, 95, 105)
            val offset = if (sw.pressed) 6f else 0f
            canvas.drawRect(sw.x - cameraX, sw.y + offset, sw.x - cameraX + sw.w, sw.y + sw.h, paint)
        }
        paint.color = Color.rgb(25, 28, 34)
        for (g in chapter.gates) {
            if (g.currentH > 2f) {
                canvas.drawRect(g.x - cameraX, g.y, g.x - cameraX + g.w, g.y + g.currentH, paint)
            }
        }

        // 5. Pushable Wooden Crates
        for (c in chapter.crates) {
            val cx = c.x - cameraX
            paint.color = Color.rgb(24, 26, 30)
            canvas.drawRect(cx, c.y, cx + c.w, c.y + c.h, paint)
            paint.color = Color.rgb(55, 60, 68)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 4f
            canvas.drawRect(cx + 4f, c.y + 4f, cx + c.w - 4f, c.y + c.h - 4f, paint)
            canvas.drawLine(cx + 4f, c.y + 4f, cx + c.w - 4f, c.y + c.h - 4f, paint)
            paint.style = Paint.Style.FILL
        }

        // 6. Hazards (Spinning Sawblades & Bear Traps)
        for (h in chapter.hazards) {
            val hx = h.x - cameraX
            if (h.type == "saw") {
                canvas.save()
                canvas.translate(hx + h.w / 2f, h.y + h.h / 2f)
                canvas.rotate(animTime * 420f)
                paint.color = Color.rgb(15, 16, 20)
                canvas.drawCircle(0f, 0f, h.w * 0.45f, paint)
                for (t in 0 until 12) {
                    canvas.rotate(30f)
                    canvas.drawRect(-4f, -h.w * 0.54f, 4f, -h.w * 0.35f, paint)
                }
                paint.color = Color.rgb(130, 138, 150)
                canvas.drawCircle(0f, 0f, 7f, paint)
                canvas.restore()
            } else {
                // Bear Trap Jaws
                paint.color = Color.rgb(12, 12, 15)
                canvas.drawRect(hx, h.y + 14f, hx + h.w, h.y + h.h, paint)
                canvas.drawRect(hx + 4f, h.y + 4f, hx + 12f, h.y + 16f, paint)
                canvas.drawRect(hx + h.w - 12f, h.y + 4f, hx + h.w - 4f, h.y + 16f, paint)
            }
        }

        // 7. Goal Beacon (And Sister Aria Silhouette on Chapter 8!)
        val gx = chapter.goalX - cameraX
        val pulse = 0.55f + 0.25f * sin(animTime * 4f)
        paint.shader = LinearGradient(gx, chapter.goalY - 80f, gx, chapter.goalY + 130f, Color.TRANSPARENT, Color.argb((pulse * 180).toInt(), 255, 255, 255), Shader.TileMode.CLAMP)
        canvas.drawRect(gx - 10f, chapter.goalY - 80f, gx + 80f, chapter.goalY + 130f, paint)
        paint.shader = null

        if (currentChapterIdx == 7) {
            // Draw Sister Aria waiting under the light!
            drawCharacterSilhouette(canvas, gx + 25f, chapter.goalY + 62f, facingRight = false, swing = 0f, isSister = true)
        }

        // 8. Draw Protagonist Boy
        val swing = if (abs(playerVx) > 10f && onGround) sin(animTime * 18f) * 9f else 0f
        drawCharacterSilhouette(canvas, playerX - cameraX, playerY, facingRight, swing, isSister = false)

        // 9. Atmospheric Rain & Volumetric Vignette
        if (chapter.bgTheme == "rainy_city" || chapter.bgTheme == "swamp_ruins") {
            paint.color = Color.argb(95, 220, 230, 240)
            paint.strokeWidth = 2f
            for (i in rainDrops.indices step 2) {
                rainDrops[i] = (rainDrops[i] - 3f + vW) % vW
                rainDrops[i + 1] = (rainDrops[i + 1] + 16f) % vH
                canvas.drawLine(rainDrops[i], rainDrops[i + 1], rainDrops[i] - 5f, rainDrops[i + 1] + 18f, paint)
            }
        }

        paint.shader = RadialGradient(vW / 2f, vH / 2f, 760f, Color.TRANSPARENT, Color.argb((chapter.fogAlpha * 255).toInt(), 0, 0, 0), Shader.TileMode.CLAMP)
        canvas.drawRect(0f, 0f, vW, vH, paint)
        paint.shader = null

        // 10. Story Narrative Subtitles & Chapter Title Banner
        if (chapterBannerTimer > 0f) {
            val alpha = (chapterBannerTimer.coerceIn(0f, 1f) * 255).toInt()
            textPaint.color = Color.argb(alpha, 255, 255, 255)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 38f
            canvas.drawText(chapter.title.uppercase(), vW / 2f, 115f, textPaint)
        }

        if (storyTimer > 0f && activeStoryText.isNotEmpty()) {
            val alpha = (storyTimer.coerceIn(0f, 1f) * 240).toInt()
            paint.color = Color.argb((alpha * 0.65f).toInt(), 8, 10, 14)
            canvas.drawRoundRect(RectF(220f, 145f, vW - 220f, 205f), 14f, 14f, paint)
            textPaint.color = Color.argb(alpha, 245, 248, 255)
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.textSize = 23f
            canvas.drawText(""$activeStoryText"", vW / 2f, 183f, textPaint)
        }

        // 11. On-Screen Controls (Left, Right, Jump, Pause, Mute)
        drawGameplayControls(canvas)
    }

    private fun drawCharacterSilhouette(canvas: Canvas, x: Float, y: Float, facingRight: Boolean, swing: Float, isSister: Boolean) {
        paint.color = Color.rgb(6, 7, 10)
        // Head
        canvas.drawCircle(x + 17f, y + 14f, 13f, paint)
        if (isSister) {
            // Hair Ponytail silhouette
            canvas.drawCircle(x + 28f, y + 12f, 7f, paint)
        }
        // Torso
        canvas.drawRoundRect(RectF(x + 8f, y + 25f, x + 26f, y + 50f), 6f, 6f, paint)
        // Legs
        canvas.drawRect(x + 9f + swing, y + 48f, x + 15f + swing, y + 68f, paint)
        canvas.drawRect(x + 19f - swing, y + 48f, x + 25f - swing, y + 68f, paint)

        // Glowing White Eyes
        paint.color = Color.WHITE
        val eyeX = if (facingRight) x + 20f else x + 9f
        canvas.drawCircle(eyeX, y + 13f, 2.3f, paint)
        canvas.drawCircle(eyeX + 5f, y + 13f, 2.3f, paint)
    }

    private fun drawGameplayControls(canvas: Canvas) {
        // Left Button
        drawControlCircle(canvas, 115f, 610f, 62f, "◀", btnLeft)
        // Right Button
        drawControlCircle(canvas, 265f, 610f, 62f, "▶", btnRight)
        // Jump Button
        drawControlCircle(canvas, 1155f, 605f, 70f, "▲", btnJump)

        // Top-Right Pause & Audio Buttons
        drawMenuButton(canvas, 1150f, 28f, 95f, 48f, "II")
        drawMenuButton(canvas, 1035f, 28f, 95f, 48f, if (soundManager.isMuted) "MUTE" else "SFX")
    }

    private fun drawControlCircle(canvas: Canvas, cx: Float, cy: Float, r: Float, symbol: String, active: Boolean) {
        paint.color = if (active) Color.argb(135, 255, 255, 255) else Color.argb(55, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        paint.color = Color.argb(140, 255, 255, 255)
        canvas.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 32f
        canvas.drawText(symbol, cx, cy + 11f, textPaint)
    }

    private fun renderPauseOverlay(canvas: Canvas) {
        paint.color = Color.argb(190, 10, 12, 16)
        canvas.drawRect(0f, 0f, vW, vH, paint)

        textPaint.color = Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 52f
        canvas.drawText("GAME PAUSED", vW / 2f, 220f, textPaint)

        drawMenuButton(canvas, vW / 2f - 170f, 290f, 340f, 68f, "RESUME")
        drawMenuButton(canvas, vW / 2f - 170f, 385f, 340f, 68f, "RESTART CHAPTER")
        drawMenuButton(canvas, vW / 2f - 170f, 480f, 340f, 68f, "MAIN MENU")
    }

    private fun renderEnding(canvas: Canvas) {
        canvas.drawColor(Color.rgb(220, 226, 235))
        textPaint.color = Color.rgb(15, 18, 24)
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 56f
        canvas.drawText("REUNITED IN THE LIGHT", vW / 2f, 240f, textPaint)

        textPaint.textSize = 28f
        canvas.drawText("You guided the lost boy through all 8 chapters and rescued Aria.", vW / 2f, 310f, textPaint)
        canvas.drawText("Thank you for playing Shadow Limbo — Developed by WYRIX", vW / 2f, 360f, textPaint)

        drawMenuButton(canvas, vW / 2f - 170f, 450f, 340f, 70f, "RETURN TO MENU")
    }

    private fun drawMenuButton(canvas: Canvas, x: Float, y: Float, w: Float, h: Float, label: String, dimmed: Boolean = false) {
        paint.color = if (dimmed) Color.argb(70, 30, 34, 40) else Color.argb(165, 20, 23, 28)
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.5f
        paint.color = if (dimmed) Color.argb(80, 150, 160, 175) else Color.argb(190, 220, 228, 240)
        canvas.drawRoundRect(RectF(x, y, x + w, y + h), 12f, 12f, paint)
        paint.style = Paint.Style.FILL

        textPaint.color = if (dimmed) Color.argb(120, 200, 205, 215) else Color.WHITE
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.textSize = 24f
        canvas.drawText(label, x + w / 2f, y + h / 2f + 9f, textPaint)
    }

    private fun drawTiledParallax(canvas: Canvas, bmp: Bitmap?, factor: Float, alpha: Int) {
        if (bmp == null) return
        paint.alpha = alpha
        val tileW = vW
        val offset = -((cameraX * factor) % tileW)
        canvas.drawBitmap(bmp, null, RectF(offset, 0f, offset + tileW, vH), paint)
        canvas.drawBitmap(bmp, null, RectF(offset + tileW, 0f, offset + tileW * 2f, vH), paint)
        paint.alpha = 255
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val sx = vW / width.toFloat().coerceAtLeast(1f)
        val sy = vH / height.toFloat().coerceAtLeast(1f)

        if (state != GameState.PLAYING) {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                val tx = event.x * sx
                val ty = event.y * sy
                handleMenuTouch(tx, ty)
            }
            return true
        }

        // Gameplay Multi-Touch Controls
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
                if (tx in 1150f..1245f && ty in 28f..76f) {
                    state = GameState.PAUSED
                    return true
                }
                if (tx in 1035f..1130f && ty in 28f..76f) {
                    soundManager.toggleMute()
                    return true
                }
            }

            if (dist(tx, ty, 115f, 610f) <= 85f) left = true
            else if (dist(tx, ty, 265f, 610f) <= 85f) right = true
            else if (dist(tx, ty, 1155f, 605f) <= 95f) jump = true
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
                        in 340f..414f -> startChapter(unlockedChapter)
                        in 440f..514f -> state = GameState.CHAPTER_SELECT
                        in 540f..604f -> soundManager.toggleMute()
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
                    val by = 155f + row * 115f
                    if (tx in bx..(bx + 490f) && ty in by..(by + 86f) && i <= unlockedChapter) {
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
