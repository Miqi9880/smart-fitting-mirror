package com.smartfitting.mirror

import android.content.Context
import android.graphics.Color
import android.widget.LinearLayout
import android.widget.TextView
import com.smartfitting.mirror.vision.*
import java.util.Locale

internal object StandingWords {
    fun summary(value: StandingAssessment?) = when (value?.cue) {
        null -> "尚未检查站位"
        StandingCue.NO_PERSON -> "请站到镜前，让身体进入画面"
        StandingCue.UNCERTAIN -> "暂未完整识别身体，请面向镜子"
        StandingCue.HEAD -> "请让头部完整入镜"
        StandingCue.TORSO -> "请让肩部和腰部完整入镜"
        StandingCue.LEGS -> "请让双腿和双脚完整入镜"
        StandingCue.OUTSIDE -> "请让全身完整处于画面内"
        StandingCue.CENTER -> "请移到画面中央"
        StandingCue.READY -> "入镜位置基本合适，请自然站立"
        StandingCue.TOO_NEAR -> "距离过近，请稍向后退"
        StandingCue.TOO_FAR -> "距离过远，请稍向前站"
    }
    fun ended(reason: GuestEndReason) = when (reason) {
        GuestEndReason.MANUAL -> "体验已结束"
        GuestEndReason.NO_PERSON -> "持续未检测到人体，体验已结束"
        GuestEndReason.INACTIVITY -> "长时间无操作，体验已结束"
        GuestEndReason.SESSION_LIMIT, GuestEndReason.SESSION_EXPIRED -> "本次体验已到期"
    }
}

internal class StandingGuidePanel(context: Context) : LinearLayout(context) {
    private val hint = TextView(context).apply {
        tag = "fitting.standing.hint"; textSize = 20f; setTextColor(Color.rgb(26, 43, 55))
        accessibilityLiveRegion = ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val detail = TextView(context).apply { tag = "fitting.standing.detail"; textSize = 13f }
    init { orientation = VERTICAL; tag = "fitting.standing.panel"; addView(hint); addView(detail); render(null, false) }
    fun render(value: StandingAssessment?, synthetic: Boolean) {
        val text = StandingWords.summary(value)
        if (hint.text.toString() != text) hint.text = text
        detail.text = (if (synthetic && value != null) "合成站位示例 · " else "") +
            if (value?.distanceM == null) "尚未测得距离；请保持头部和双脚完整入镜。" else
                String.format(Locale.ROOT, "测得约 %.2f 米；最佳距离需按现场标定。", value.distanceM)
    }
}
