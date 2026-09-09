package com.github.andreyasadchy.xtra.ui.chat

import android.animation.ValueAnimator
import android.view.animation.DecelerateInterpolator
import android.widget.ImageView
import android.widget.TextView
import androidx.core.view.isVisible
import com.github.andreyasadchy.xtra.util.TwitchApiHelper
import kotlin.math.abs

internal class ChannelPointsBalanceAnimator(
    private val icon: ImageView,
    private val balance: TextView,
    private val delta: TextView,
) {
    private var animationGeneration = 0

    fun animate(previousBalance: Int?, currentBalance: Int) {
        val change = currentBalance - (previousBalance ?: return)
        if (change == 0) return

        cancel()
        if (!ValueAnimator.areAnimatorsEnabled()) return

        val generation = ++animationGeneration
        val magnitude = if (change == Int.MIN_VALUE) Int.MAX_VALUE else abs(change)
        val formattedChange = TwitchApiHelper.formatCount(magnitude, compact = true)
        delta.text = if (change > 0) "+$formattedChange" else "-$formattedChange"
        delta.setTextColor(balance.currentTextColor)
        delta.isVisible = true
        delta.alpha = 0f
        delta.scaleX = 0.82f
        delta.scaleY = 0.82f
        delta.translationY = 2f

        delta.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .translationY(0f)
            .setDuration(150L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (animationGeneration != generation) return@withEndAction
                delta.animate()
                    .alpha(0f)
                    .translationY(-2f)
                    .setStartDelay(550L)
                    .setDuration(300L)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction {
                        if (animationGeneration == generation) {
                            delta.isVisible = false
                        }
                    }
                    .start()
            }
            .start()

        icon.animate()
            .scaleX(1.16f)
            .scaleY(1.16f)
            .setDuration(120L)
            .setInterpolator(DecelerateInterpolator())
            .withEndAction {
                if (animationGeneration != generation) return@withEndAction
                icon.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(220L)
                    .setInterpolator(DecelerateInterpolator())
                    .start()
            }
            .start()
    }

    fun cancel() {
        animationGeneration++
        delta.animate().cancel()
        icon.animate().cancel()
        delta.isVisible = false
        delta.alpha = 0f
        delta.scaleX = 1f
        delta.scaleY = 1f
        delta.translationY = 0f
        icon.scaleX = 1f
        icon.scaleY = 1f
    }
}
