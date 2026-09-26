package com.meego.launcher.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 旗舰级高惯性滑行特性的 MeeGo FlingBehavior
 * 基于原生高品质 Spline-based 衰减曲线，配合动态速度自适应冲量增益与极低启动死区，
 * 解决原生 Compose 默认减速过快、滑动行程过短（滑不动、阻滞感）的痛点，
 * 实现轻扫飘逸、长程滑行与 120Hz 丝滑高刷质感。
 */
class MeeGoSmoothFlingBehavior(
    private val flingDecay: DecayAnimationSpec<Float>
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val absV = abs(initialVelocity)
        if (absV > 1f) {
            // 根据初速度动态阶梯增益：轻扫显著延长滑行，中高速长程滑行丝滑平稳
            val multiplier = when {
                absV < 1000f -> 1.35f
                absV < 3500f -> 1.70f
                else -> 1.85f
            }
            val boostedVelocity = initialVelocity * multiplier
            var velocityLeft = boostedVelocity
            var lastValue = 0f
            AnimationState(
                initialValue = 0f,
                initialVelocity = boostedVelocity
            ).animateDecay(flingDecay) {
                val delta = value - lastValue
                val consumed = scrollBy(delta)
                lastValue = value
                velocityLeft = this.velocity
                if (abs(delta - consumed) > 0.5f) {
                    cancelAnimation()
                }
            }
            return velocityLeft
        } else {
            return initialVelocity
        }
    }
}

@Composable
fun rememberMeeGoFlingBehavior(): FlingBehavior {
    val splineDecay = rememberSplineBasedDecay<Float>()
    return remember(splineDecay) {
        MeeGoSmoothFlingBehavior(splineDecay)
    }
}

/**
 * Nokia N9 MeeGo 经典触顶/触底对数阻尼物理橡皮筋与弹性回弹控制器
 */
@Stable
class MeeGoBounceScrollController(
    val maxOverscrollPx: Float,
    private val coroutineScope: CoroutineScope
) {
    var overscrollOffset by mutableFloatStateOf(0f)
        private set

    private val animatable = Animatable(0f)
    private var reboundJob: Job? = null

    fun reset() {
        reboundJob?.cancel()
        reboundJob = null
        overscrollOffset = 0f
    }

    /**
     * 手动拖拽驱动：先消化已有越界拉伸位移；
     * 返回真正应交给列表滚动的剩余位移（与原生 NestedScrollConnection.onPreScroll 语义一致）。
     */
    fun consumeOverscrollForDrag(dy: Float): Float {
        reboundJob?.cancel()
        reboundJob = null
        val current = overscrollOffset
        if (abs(current) < 0.5f) {
            if (current != 0f) overscrollOffset = 0f
            return dy
        }
        return if (current > 0f && dy < 0f) {
            val target = current + dy
            if (target >= 0f) {
                overscrollOffset = target
                0f
            } else {
                overscrollOffset = 0f
                target
            }
        } else if (current < 0f && dy > 0f) {
            val target = current + dy
            if (target <= 0f) {
                overscrollOffset = target
                0f
            } else {
                overscrollOffset = 0f
                target
            }
        } else {
            dy
        }
    }

    /**
     * 手动拖拽驱动：列表已到底/顶后，把未能消费的剩余位移转成非线性阻尼橡皮筋拉伸。
     */
    fun pullOverscroll(leftover: Float) {
        if (abs(leftover) < 0.01f) return
        reboundJob?.cancel()
        reboundJob = null
        val current = overscrollOffset
        val currentRatio = (abs(current) / maxOverscrollPx).coerceIn(0f, 1f)
        val damping = (0.42f * (1f - currentRatio * 0.65f)).coerceIn(0.12f, 0.42f)
        overscrollOffset = (current + leftover * damping).coerceIn(-maxOverscrollPx, maxOverscrollPx)
    }

    /**
     * 手动拖拽/惯性结束驱动：越界则弹簧平滑归零，高速撞界则先做一次冲击吸能再归零。
     */
    fun releaseOverscroll(velocity: Float) {
        val current = overscrollOffset
        if (abs(current) < 0.5f) {
            overscrollOffset = 0f
            return
        }
        reboundJob?.cancel()
        val absV = abs(velocity)
        reboundJob = coroutineScope.launch {
            try {
                animatable.snapTo(overscrollOffset)
                if (absV > 120f) {
                    val maxImpact = maxOverscrollPx * 0.35f
                    val impactTarget = (velocity * 0.022f).coerceIn(-maxImpact, maxImpact)
                    animatable.animateTo(
                        targetValue = impactTarget,
                        animationSpec = spring(dampingRatio = 0.65f, stiffness = 520f)
                    ) {
                        overscrollOffset = this.value
                    }
                }
                animatable.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(dampingRatio = 0.78f, stiffness = 380f)
                ) {
                    overscrollOffset = this.value
                }
            } finally {
                overscrollOffset = 0f
            }
        }
    }

    val nestedScrollConnection: NestedScrollConnection = object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            // 仅当用户手指物理接触屏幕拖拽时才取消回弹动画（由手指接管控制）
            if (source == NestedScrollSource.Drag && abs(available.y) > 0.5f) {
                reboundJob?.cancel()
            }
            val current = overscrollOffset
            if (abs(current) < 0.5f) {
                if (current != 0f) overscrollOffset = 0f
                return Offset.Zero
            }

            // 仅当处于越界状态，且用户正在向回拖动时，优先消费位移回弹归零
            return if (current > 0f && available.y < 0f) {
                val target = current + available.y
                if (target >= 0f) {
                    overscrollOffset = target
                    Offset(0f, available.y)
                } else {
                    overscrollOffset = 0f
                    Offset(0f, -current)
                }
            } else if (current < 0f && available.y > 0f) {
                val target = current + available.y
                if (target <= 0f) {
                    overscrollOffset = target
                    Offset(0f, available.y)
                } else {
                    overscrollOffset = 0f
                    Offset(0f, -current)
                }
            } else {
                Offset.Zero
            }
        }

        override fun onPostScroll(
            consumed: Offset,
            available: Offset,
            source: NestedScrollSource
        ): Offset {
            // 关键防卡死原则：仅当手指物理拖拽（Drag）到达边界且有剩余位移时才拉伸橡皮筋
            // Fling（惯性滑行）必须放行给 FlingBehavior 与 onPostFling 处理，绝不在 onPostScroll 中累加消费位移，
            // 否则会导致 Fling 衰减动画误以为位移已被消费而无法触发边界中断，进而卡死在最大越界处无法回弹！
            if (source == NestedScrollSource.Drag && available.y != 0f) {
                reboundJob?.cancel()
                val current = overscrollOffset
                val currentRatio = (abs(current) / maxOverscrollPx).coerceIn(0f, 1f)
                val damping = (0.42f * (1f - currentRatio * 0.65f)).coerceIn(0.12f, 0.42f)
                val newOffset = (current + available.y * damping).coerceIn(-maxOverscrollPx, maxOverscrollPx)
                overscrollOffset = newOffset
                return Offset(0f, available.y)
            }
            return Offset.Zero
        }

        override suspend fun onPreFling(available: Velocity): Velocity {
            val current = overscrollOffset
            if (abs(current) > 0.5f) {
                reboundJob?.cancel()
                reboundJob = coroutineScope.launch {
                    try {
                        animatable.snapTo(overscrollOffset)
                        animatable.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 0.78f,
                                stiffness = 380f
                            )
                        ) {
                            overscrollOffset = this.value
                        }
                    } finally {
                        overscrollOffset = 0f
                    }
                }
                return available
            }
            return Velocity.Zero
        }

        override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity {
            val absV = abs(available.y)
            if (absV > 120f) {
                reboundJob?.cancel()
                val maxImpact = maxOverscrollPx * 0.35f
                val impactTarget = (available.y * 0.022f).coerceIn(-maxImpact, maxImpact)
                reboundJob = coroutineScope.launch {
                    try {
                        animatable.snapTo(overscrollOffset)
                        animatable.animateTo(
                            targetValue = impactTarget,
                            animationSpec = spring(
                                dampingRatio = 0.65f,
                                stiffness = 520f
                            )
                        ) {
                            overscrollOffset = this.value
                        }
                        animatable.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 0.78f,
                                stiffness = 360f
                            )
                        ) {
                            overscrollOffset = this.value
                        }
                    } finally {
                        overscrollOffset = 0f
                    }
                }
                return Velocity(0f, available.y)
            } else if (reboundJob?.isActive != true && abs(overscrollOffset) > 0.5f) {
                reboundJob = coroutineScope.launch {
                    try {
                        animatable.snapTo(overscrollOffset)
                        animatable.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = 0.78f,
                                stiffness = 360f
                            )
                        ) {
                            overscrollOffset = this.value
                        }
                    } finally {
                        overscrollOffset = 0f
                    }
                }
            }
            return Velocity.Zero
        }
    }
}

@Composable
fun rememberMeeGoBounceController(
    maxOverscrollDp: Dp = 90.dp
): MeeGoBounceScrollController {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val maxOverscrollPx = with(density) { maxOverscrollDp.toPx() }
    return remember(maxOverscrollPx, coroutineScope) {
        MeeGoBounceScrollController(maxOverscrollPx, coroutineScope)
    }
}
