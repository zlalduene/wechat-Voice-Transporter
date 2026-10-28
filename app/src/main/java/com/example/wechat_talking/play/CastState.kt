package com.example.wechat_talking.play

import kotlinx.coroutines.flow.MutableStateFlow

/** 播放引擎对外的状态。 */
sealed interface CastState {
    data object Idle : CastState
    /** @param remainingMs 距离真正出声还要多久 */
    data class Countdown(val remainingMs: Long) : CastState
    data class Playing(val elapsedMs: Long, val totalMs: Long) : CastState
    data class Finished(val capturedFile: String?, val capturedRmsDb: Float) : CastState
    data class Failed(val message: String) : CastState
}

enum class OutputRoute(val label: String) {
    SPEAKER("外放喇叭"),
    EARPIECE("听筒（贴耳）"),
}

object CastBus {
    val state = MutableStateFlow<CastState>(CastState.Idle)
    /** 回采（模拟微信听到的效果）的实时电平，0..1 */
    val captureLevel = MutableStateFlow(0f)

    fun reset() {
        state.value = CastState.Idle
        captureLevel.value = 0f
    }
}
