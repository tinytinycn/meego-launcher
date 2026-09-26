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
 * MeeGo 滚动物理参数集（§3.4）。
 *
 * 设计约束：**默认值与优化前逐位一致**，即 Default 就是当前线上手感，
 * 本次性能优化不改变任何物理观感；此处只是把这些"魔法数字"显式化，
 * 便于后续 A/B 对照（例如与 Trebuchet 的短行程手感对比）而不必改代码结构。
 *
 * 注意（来自 docs/GRID_SCROLL_PERFORMANCE_PLAN.md §3.4 的结论）：
 * 降低 fling 增益只是"让滑行更短、更少暴露中间帧"，**不是**卡顿的根因修复；
 * 不得用 A/B 档位来替代对重组/绘制/预取成本的真实优化。
 */
@Stable
data class ScrollPhysicsConfig(
    /** 轻扫（|v| < [lightFlingVelocityThreshold]）时的冲量增益。 */
    val lightFlingGain: Float = 1.35f,
    /** 中速滑动的冲量增益。 */
    val mediumFlingGain: Float = 1.70f,
    /** 高速长程滑行的冲量增益。 */
    val highFlingGain: Float = 1.85f,
    /** 轻扫 / 中速 的初速度分界（px/s）。 */
    val lightFlingVelocityThreshold: Float = 1000f,
    /** 中速 / 高速 的初速度分界（px/s）。 */
    val mediumFlingVelocityThreshold: Float = 3500f,
    /** performFling 视为有效滑行的最小初速度（px/s）。 */
    val minFlingVelocity: Float = 1f,
    /** 滑行中"请求位移未被列表消费"到判定为撞界中断的阈值（px）。 */
    val flingBoundaryEpsilon: Float = 0.5f,
    /** 触顶/触底最大橡皮筋拉伸（dp）。 */
    val maxOverscrollDp: Dp = 90.dp,
    /** 橡皮筋阻尼基数，命中率越高阻尼越低（越界越远越"沉"）。 */
    val overscrollDamping: Float = 0.42f,
    /** 橡皮筋阻尼下限（完全拉满时的最小阻尼）。 */
    val overscrollDampingMin: Float = 0.12f,
    /** 橡皮筋阻尼随拉伸比例的衰减系数。 */
    val overscrollDampingFalloff: Float = 0.65f,
    /** 撞界瞬间的冲击吸能速度阈值（px/s）。 */
    val impactVelocityThreshold: Float = 120f,
    /** 撞界冲击目标位移占最大拉伸的比例。 */
    val impactRatio: Float = 0.35f,
    /** 撞界冲击位移换算系数。 */
    val impactScale: Float = 0.022f,
    /** 冲击吸能阶段弹簧：阻尼比 / 刚度。 */
    val impactDampingRatio: Float = 0.65f,
    val impactStiffness: Float = 520f,
    /** 回弹归零阶段弹簧：阻尼比 / 刚度。 */
    val reboundDampingRatio: Float = 0.78f,
    val reboundStiffness: Float = 380f,
    /**
     * NestedScrollConnection.onPostFling 路径的回弹刚度。
     * 历史上该路径与手动 releaseOverscroll 路径取值不同（360 vs 380），此处显式保留，
     * 避免"顺手统一"造成手感变化。
     */
    val postFlingReboundStiffness: Float = 360f
) {
    companion object {
        /** 与优化前完全一致的默认手感。 */
        val Default = ScrollPhysicsConfig()

        /**
         * 对照档：增益 1.0x + 更短行程，用于判定"滑不动/阻滞"究竟来自摩擦还是掉帧。
         * **默认不启用**，仅供开发期对照（docs §6 失败模式 2）。
         */
        val ShortTravel = Default.copy(
            lightFlingGain = 1.0f,
            mediumFlingGain = 1.0f,
            highFlingGain = 1.0f
        )
    }
}

/**
 * 旗舰级高惯性滑行特性的 MeeGo FlingBehavior
 * 基于原生高品质 Spline-based 衰减曲线，配合动态速度自适应冲量增益与极低启动死区，
 * 解决原生 Compose 默认减速过快、滑动行程过短（滑不动、阻滞感）的痛点，
 * 实现轻扫飘逸、长程滑行与 120Hz 丝滑高刷质感。
 */
class MeeGoSmoothFlingBehavior(
    private val flingDecay: DecayAnimationSpec<Float>,
    private val config: ScrollPhysicsConfig = ScrollPhysicsConfig.Default
) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        val absV = abs(initialVelocity)
        if (absV > config.minFlingVelocity) {
            // 根据初速度动态阶梯增益：轻扫显著延长滑行，中高速长程滑行丝滑平稳
            val multiplier = when {
                absV < config.lightFlingVelocityThreshold -> config.lightFlingGain
                absV < config.mediumFlingVelocityThreshold -> config.mediumFlingGain
                else -> config.highFlingGain
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
                // 边界中断：本帧请求的位移没能被列表消费，说明已经撞到可滚动范围边缘。
                // 立即 cancelAnimation 退出，把剩余 velocityLeft 交回调用方
                // （AppGridScreen 用它驱动 releaseOverscroll 回弹），避免在边界空转。
                if (abs(delta - consumed) > config.flingBoundaryEpsilon) {
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
fun rememberMeeGoFlingBehavior(config: ScrollPhysicsConfig = ScrollPhysicsConfig.Default): FlingBehavior {
    val splineDecay = rememberSplineBasedDecay<Float>()
    return remember(splineDecay, config) {
        MeeGoSmoothFlingBehavior(splineDecay, config)
    }
}

/**
 * Nokia N9 MeeGo 经典触顶/触底对数阻尼物理橡皮筋与弹性回弹控制器
 */
@Stable
class MeeGoBounceScrollController(
    val maxOverscrollPx: Float,
    private val coroutineScope: CoroutineScope,
    private val config: ScrollPhysicsConfig = ScrollPhysicsConfig.Default
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
        val damping = (config.overscrollDamping *
            (1f - currentRatio * config.overscrollDampingFalloff))
            .coerceIn(config.overscrollDampingMin, config.overscrollDamping)
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
                if (absV > config.impactVelocityThreshold) {
                    val maxImpact = maxOverscrollPx * config.impactRatio
                    val impactTarget = (velocity * config.impactScale).coerceIn(-maxImpact, maxImpact)
                    animatable.animateTo(
                        targetValue = impactTarget,
                        animationSpec = spring(
                            dampingRatio = config.impactDampingRatio,
                            stiffness = config.impactStiffness
                        )
                    ) {
                        overscrollOffset = this.value
                    }
                }
                animatable.animateTo(
                    targetValue = 0f,
                    animationSpec = spring(
                        dampingRatio = config.reboundDampingRatio,
                        stiffness = config.reboundStiffness
                    )
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
                val damping = (config.overscrollDamping *
                    (1f - currentRatio * config.overscrollDampingFalloff))
                    .coerceIn(config.overscrollDampingMin, config.overscrollDamping)
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
                                dampingRatio = config.reboundDampingRatio,
                                stiffness = config.reboundStiffness
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
            if (absV > config.impactVelocityThreshold) {
                reboundJob?.cancel()
                val maxImpact = maxOverscrollPx * config.impactRatio
                val impactTarget = (available.y * config.impactScale).coerceIn(-maxImpact, maxImpact)
                reboundJob = coroutineScope.launch {
                    try {
                        animatable.snapTo(overscrollOffset)
                        animatable.animateTo(
                            targetValue = impactTarget,
                            animationSpec = spring(
                                dampingRatio = config.impactDampingRatio,
                                stiffness = config.impactStiffness
                            )
                        ) {
                            overscrollOffset = this.value
                        }
                        animatable.animateTo(
                            targetValue = 0f,
                            animationSpec = spring(
                                dampingRatio = config.reboundDampingRatio,
                                stiffness = config.postFlingReboundStiffness
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
                                dampingRatio = config.reboundDampingRatio,
                                stiffness = config.postFlingReboundStiffness
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
    maxOverscrollDp: Dp = ScrollPhysicsConfig.Default.maxOverscrollDp,
    config: ScrollPhysicsConfig = ScrollPhysicsConfig.Default
): MeeGoBounceScrollController {
    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()
    val maxOverscrollPx = with(density) { maxOverscrollDp.toPx() }
    return remember(maxOverscrollPx, coroutineScope, config) {
        MeeGoBounceScrollController(maxOverscrollPx, coroutineScope, config)
    }
}
