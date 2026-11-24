package com.wyrix.shadowlimbo

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool

class SoundManager(private val context: Context) {
    private var bgmPlayer: MediaPlayer? = null
    private var currentTrack: String = ""
    var isMuted = false

    private val soundPool: SoundPool
    private var stepId = 0
    private var jumpId = 0
    private var deathId = 0
    private var switchId = 0
    private var goalId = 0

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_GAME)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder().setMaxStreams(6).setAudioAttributes(attrs).build()
        stepId = loadSfx("audio/sfx_footstep.wav")
        jumpId = loadSfx("audio/sfx_jump.wav")
        deathId = loadSfx("audio/sfx_death.wav")
        switchId = loadSfx("audio/sfx_switch.wav")
        goalId = loadSfx("audio/sfx_chapter_clear.wav")
    }

    private fun loadSfx(assetPath: String): Int {
        return try {
            val afd = context.assets.openFd(assetPath)
            val id = soundPool.load(afd, 1)
            afd.close()
            id
        } catch (e: Exception) {
            0
        }
    }

    fun playChapterMusic(assetPath: String) {
        if (currentTrack == assetPath && bgmPlayer != null) {
            if (!isMuted && bgmPlayer?.isPlaying == false) bgmPlayer?.start()
            return
        }
        stopMusic()
        currentTrack = assetPath
        try {
            val afd = context.assets.openFd(assetPath)
            bgmPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                val vol = if (isMuted) 0f else 0.75f
                setVolume(vol, vol)
                prepare()
                start()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun toggleMute() {
        isMuted = !isMuted
        val vol = if (isMuted) 0f else 0.75f
        bgmPlayer?.setVolume(vol, vol)
    }

    fun playStep() { if (!isMuted && stepId != 0) soundPool.play(stepId, 0.45f, 0.45f, 1, 0, 1f) }
    fun playJump() { if (!isMuted && jumpId != 0) soundPool.play(jumpId, 0.65f, 0.65f, 1, 0, 1f) }
    fun playDeath() { if (!isMuted && deathId != 0) soundPool.play(deathId, 0.85f, 0.85f, 2, 0, 1f) }
    fun playSwitch() { if (!isMuted && switchId != 0) soundPool.play(switchId, 0.75f, 0.75f, 1, 0, 1f) }
    fun playGoal() { if (!isMuted && goalId != 0) soundPool.play(goalId, 0.85f, 0.85f, 2, 0, 1f) }

    fun pauseMusic() {
        try { if (bgmPlayer?.isPlaying == true) bgmPlayer?.pause() } catch (_: Exception) {}
    }

    fun resumeMusic() {
        try { if (!isMuted && bgmPlayer != null && bgmPlayer?.isPlaying == false) bgmPlayer?.start() } catch (_: Exception) {}
    }

    fun stopMusic() {
        try {
            bgmPlayer?.stop()
            bgmPlayer?.release()
        } catch (_: Exception) {}
        bgmPlayer = null
    }
}
