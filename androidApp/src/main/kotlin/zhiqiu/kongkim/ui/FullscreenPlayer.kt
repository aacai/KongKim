@file:OptIn(ExperimentalMaterial3Api::class)

package zhiqiu.kongkim.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.AudioManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Brightness6
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 全屏播放页，交互对齐 YouTube 的主流方案：
 * - 单击画面：显示 / 收起控制栏；播放中 3.5s 自动收起，暂停时常驻
 * - 左右半屏双击：±10 秒，带涟漪反馈
 * - 水平拖动：拖动 seek，气泡实时预览目标时间
 * - 左半屏竖直拖动：屏幕亮度；右半屏竖直拖动：媒体音量
 * - 播完显示重播按钮；缓冲中显示加载圈
 */
@Composable
fun FullscreenPlayer(
    state: PlayerUiState,
    position: StateFlow<Long>,
    sessionPlayer: Player?,
    onTogglePlay: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onRestart: () -> Unit,
    onRetry: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onExit: () -> Unit,
) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val audioManager =
        remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxVolume = remember(audioManager) {
        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    }
    var volume by remember {
        mutableIntStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC))
    }
    // -1 表示跟随系统；进入全屏时记住原值，退出时还原
    val systemBrightness = remember(activity) {
        activity?.window?.attributes?.screenBrightness ?: -1f
    }
    var brightness by remember {
        mutableFloatStateOf(if (systemBrightness >= 0.02f) systemBrightness else 0.5f)
    }

    BackHandler(onBack = onExit)

    // 横屏 + 沉浸式，退出全屏时恢复竖屏、系统栏和亮度
    DisposableEffect(activity) {
        val window = activity?.window
        val previousOrientation = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val insets = window?.let { WindowInsetsControllerCompat(it, it.decorView) }
        insets?.hide(WindowInsetsCompat.Type.systemBars())
        insets?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            activity?.requestedOrientation = previousOrientation
                ?: ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            insets?.show(WindowInsetsCompat.Type.systemBars())
            window?.let { w ->
                val lp = w.attributes
                lp.screenBrightness = systemBrightness
                w.attributes = lp
            }
        }
    }

    val positionMs by position.collectAsStateWithLifecycle()
    val durationMs = state.durationMs
    val ended = durationMs > 0L && positionMs >= durationMs
    val durationState = rememberUpdatedState(durationMs)

    var controlsVisible by remember { mutableStateOf(true) }
    var interactionTick by remember { mutableIntStateOf(0) }

    fun pokeControls() {
        controlsVisible = true
        interactionTick += 1
    }

    // 拖动方向：水平拖动 seek 时控制栏常驻，松手才重新计时
    var dragAxis by remember { mutableStateOf(DragAxis.None) }

    // 播放中自动收起控制栏；暂停、出错、播完时常驻
    LaunchedEffect(interactionTick, state.isPlaying, dragAxis) {
        if (controlsVisible && state.isPlaying && dragAxis == DragAxis.None) {
            delay(CONTROLS_AUTO_HIDE_MS)
            controlsVisible = false
        }
    }
    LaunchedEffect(ended) {
        if (ended) pokeControls()
    }

    var playbackState by remember {
        mutableIntStateOf(sessionPlayer?.playbackState ?: Player.STATE_IDLE)
    }
    LaunchedEffect(sessionPlayer) {
        val player = sessionPlayer
        if (player == null) {
            playbackState = Player.STATE_IDLE
            return@LaunchedEffect
        }
        playbackState = player.playbackState
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(newState: Int) {
                playbackState = newState
            }
        }
        player.addListener(listener)
        try {
            awaitCancellation()
        } finally {
            player.removeListener(listener)
        }
    }
    val buffering = playbackState == Player.STATE_BUFFERING

    var bufferedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(sessionPlayer, controlsVisible) {
        if (!controlsVisible) return@LaunchedEffect
        while (isActive) {
            val buffered = sessionPlayer?.bufferedPosition ?: C.TIME_UNSET
            bufferedMs = if (buffered == C.TIME_UNSET || buffered < 0L) 0L else buffered
            delay(500L)
        }
    }

    var scrubBaseMs by remember { mutableLongStateOf(0L) }
    var scrubDeltaMs by remember { mutableLongStateOf(0L) }
    var verticalOnLeft by remember { mutableStateOf(true) }
    var rippleTrigger by remember { mutableIntStateOf(0) }
    var rippleForward by remember { mutableStateOf(true) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        StageSurface(sessionPlayer = sessionPlayer)

        if (state.audioOnly) {
            AudioStageOverlay(modifier = Modifier.align(Alignment.Center))
        }

        // 手势层：单击 / 双击 / 拖动，控制栏在它上面，按钮各自消费点击
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = {
                            if (controlsVisible) {
                                controlsVisible = false
                            } else {
                                pokeControls()
                            }
                        },
                        onDoubleTap = { offset ->
                            val leftSide = offset.x < size.width / 2f
                            if (leftSide) onSkipBackward() else onSkipForward()
                            rippleForward = !leftSide
                            rippleTrigger += 1
                        }
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            verticalOnLeft = offset.x < size.width / 2f
                            dragAxis = DragAxis.None
                            scrubBaseMs = positionMs
                            scrubDeltaMs = 0L
                            pokeControls()
                        },
                        onDrag = { _, dragAmount ->
                            if (dragAxis == DragAxis.None) {
                                dragAxis = if (abs(dragAmount.x) > abs(dragAmount.y)) {
                                    DragAxis.Scrub
                                } else {
                                    DragAxis.Adjust
                                }
                            }
                            when (dragAxis) {
                                DragAxis.Scrub -> {
                                    val duration = durationState.value
                                    if (duration > 0L) {
                                        scrubDeltaMs +=
                                            (dragAmount.x / size.width * duration).toLong()
                                    }
                                }

                                DragAxis.Adjust -> {
                                    val fraction = dragAmount.y / size.height.toFloat()
                                    if (verticalOnLeft) {
                                        brightness = (brightness - fraction).coerceIn(0.02f, 1f)
                                        activity?.window?.let { window ->
                                            val lp = window.attributes
                                            lp.screenBrightness = brightness
                                            window.attributes = lp
                                        }
                                    } else {
                                        val next = (volume - fraction * maxVolume)
                                            .roundToInt()
                                            .coerceIn(0, maxVolume)
                                        if (next != volume) {
                                            volume = next
                                            audioManager.setStreamVolume(
                                                AudioManager.STREAM_MUSIC,
                                                next,
                                                0
                                            )
                                        }
                                    }
                                }

                                DragAxis.None -> Unit
                            }
                        },
                        onDragEnd = {
                            if (dragAxis == DragAxis.Scrub && durationState.value > 0L) {
                                val duration = durationState.value
                                onSeek((scrubBaseMs + scrubDeltaMs).coerceIn(0L, duration))
                            }
                            dragAxis = DragAxis.None
                        },
                        onDragCancel = { dragAxis = DragAxis.None }
                    )
                }
        )

        // 双击 ±10s 的涟漪反馈，出现在被点的那一侧
        if (rippleTrigger > 0) {
            key(rippleTrigger) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 44.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (rippleForward) {
                        Spacer(Modifier.weight(1f))
                        SeekRipple(forward = true)
                    } else {
                        SeekRipple(forward = false)
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        // 竖直拖动时的亮度 / 音量指示
        if (dragAxis == DragAxis.Adjust) {
            val level = if (verticalOnLeft) brightness else volume.toFloat() / maxVolume
            VerticalLevelIndicator(
                icon = if (verticalOnLeft) {
                    Icons.Filled.Brightness6
                } else if (volume == 0) {
                    Icons.Filled.VolumeOff
                } else {
                    Icons.Filled.VolumeUp
                },
                level = level,
                modifier = Modifier.align(
                    if (verticalOnLeft) Alignment.CenterStart else Alignment.CenterEnd
                )
            )
        }

        // 水平拖动 seek 时的时间气泡
        if (dragAxis == DragAxis.Scrub && durationMs > 0L) {
            val targetMs = (scrubBaseMs + scrubDeltaMs).coerceIn(0L, durationMs)
            Surface(
                color = Color.Black.copy(alpha = 0.75f),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 92.dp)
            ) {
                Text(
                    text = "${PlayerViewModel.formatTime(targetMs, durationMs)} / " +
                        PlayerViewModel.formatTime(durationMs, durationMs),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                )
            }
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(tween(150)),
            exit = fadeOut(tween(200))
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                FullscreenTopBar(
                    state = state,
                    onSpeedChange = { pokeControls(); onSpeedChange(it) },
                    onExit = onExit,
                    modifier = Modifier.align(Alignment.TopCenter)
                )

                FullscreenCenterControls(
                    state = state,
                    ended = ended,
                    buffering = buffering,
                    onTogglePlay = { pokeControls(); onTogglePlay() },
                    onReplay = { onRestart(); onTogglePlay() },
                    onSkipBackward = { pokeControls(); onSkipBackward() },
                    onSkipForward = { pokeControls(); onSkipForward() },
                    modifier = Modifier.align(Alignment.Center)
                )

                state.errorMessage?.let { message ->
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 92.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(start = 14.dp, end = 6.dp)
                        ) {
                            Text(
                                text = message,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                            TextButton(onClick = onRetry) {
                                Text(
                                    text = "重试",
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    }
                }

                FullscreenBottomBar(
                    positionMs = positionMs,
                    bufferedMs = bufferedMs,
                    durationMs = durationMs,
                    active = dragAxis != DragAxis.None,
                    onExit = onExit,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }
        }

        if (sessionPlayer == null) {
            Text(
                text = "正在连接播放服务…",
                color = Color.White.copy(alpha = 0.8f),
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 110.dp)
            )
        }
    }
}

/** 拖动方向：未定 / 水平拖动 seek / 竖直拖动调亮度音量 */
private enum class DragAxis { None, Scrub, Adjust }

private const val CONTROLS_AUTO_HIDE_MS = 3_500L

@Composable
private fun FullscreenTopBar(
    state: PlayerUiState,
    onSpeedChange: (Float) -> Unit,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var speedMenuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 6.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onExit) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "退出全屏",
                tint = Color.White
            )
        }
        Spacer(Modifier.width(2.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = state.track.label,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "${if (state.audioOnly) "音频" else "画面"}模式 · " + when {
                    state.errorMessage != null -> "播放异常"
                    state.isPlaying -> "正在播放"
                    else -> "已暂停"
                },
                color = Color.White.copy(alpha = 0.75f),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Box {
            TextButton(onClick = { speedMenuOpen = true }) {
                Text(
                    text = PlayerViewModel.formatSpeed(state.speed),
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            DropdownMenu(
                expanded = speedMenuOpen,
                onDismissRequest = { speedMenuOpen = false }
            ) {
                PlayerViewModel.SPEED_OPTIONS.forEach { option ->
                    DropdownMenuItem(
                        text = { Text(PlayerViewModel.formatSpeed(option)) },
                        onClick = {
                            onSpeedChange(option)
                            speedMenuOpen = false
                        },
                        trailingIcon = {
                            if (option == state.speed) {
                                Icon(Icons.Filled.Check, contentDescription = null)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun FullscreenCenterControls(
    state: PlayerUiState,
    ended: Boolean,
    buffering: Boolean,
    onTogglePlay: () -> Unit,
    onReplay: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(26.dp)
    ) {
        StageIconButton(
            icon = Icons.Filled.Replay10,
            description = "后退 $SKIP_SECONDS 秒",
            size = 56.dp,
            iconSize = 28.dp,
            onClick = onSkipBackward
        )
        when {
            buffering -> Box(
                modifier = Modifier.size(84.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    color = Color.White,
                    strokeWidth = 3.dp,
                    modifier = Modifier.size(40.dp)
                )
            }

            ended -> StageIconButton(
                icon = Icons.Filled.Replay,
                description = "重新播放",
                size = 84.dp,
                iconSize = 44.dp,
                onClick = onReplay
            )

            else -> StageIconButton(
                icon = if (state.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                description = if (state.isPlaying) "暂停" else "播放",
                size = 84.dp,
                iconSize = 44.dp,
                onClick = onTogglePlay
            )
        }
        StageIconButton(
            icon = Icons.Filled.Forward10,
            description = "快进 $SKIP_SECONDS 秒",
            size = 56.dp,
            iconSize = 28.dp,
            onClick = onSkipForward
        )
    }
}

@Composable
private fun FullscreenBottomBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    active: Boolean,
    onExit: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(
                Brush.verticalGradient(
                    listOf(Color.Transparent, Color.Black.copy(alpha = 0.6f))
                )
            )
            .systemBarsPadding()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = PlayerViewModel.formatTime(positionMs, durationMs),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
        PlayerProgressBar(
            positionMs = positionMs,
            bufferedMs = bufferedMs,
            durationMs = durationMs,
            active = active,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
        )
        Text(
            text = "-" + PlayerViewModel.formatTime(
                (durationMs - positionMs).coerceAtLeast(0L),
                durationMs
            ),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
        IconButton(onClick = onExit) {
            Icon(
                imageVector = Icons.Filled.FullscreenExit,
                contentDescription = "退出全屏",
                tint = Color.White
            )
        }
    }
}

@Composable
private fun StageIconButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    iconSize: Dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.16f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = description,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

/** 细进度条：已播 + 缓冲 + 手柄，控制栏可见或拖动时加粗 */
@Composable
private fun PlayerProgressBar(
    positionMs: Long,
    bufferedMs: Long,
    durationMs: Long,
    active: Boolean,
    modifier: Modifier = Modifier,
) {
    val played = if (durationMs > 0L) (positionMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val buffered = if (durationMs > 0L) (bufferedMs.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
    val barHeight = if (active) 5.dp else 3.dp
    Canvas(modifier = modifier.height(20.dp)) {
        val centerY = size.height / 2f
        val stroke = barHeight.toPx()
        val full = size.width
        drawLine(
            color = Color.White.copy(alpha = 0.25f),
            start = Offset(0f, centerY),
            end = Offset(full, centerY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        if (buffered > played) {
            drawLine(
                color = Color.White.copy(alpha = 0.45f),
                start = Offset(0f, centerY),
                end = Offset(full * buffered, centerY),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
        }
        drawLine(
            color = Color.White,
            start = Offset(0f, centerY),
            end = Offset(full * played, centerY),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )
        if (active) {
            drawCircle(
                color = Color.White,
                radius = 6.dp.toPx(),
                center = Offset(full * played, centerY)
            )
        }
    }
}

/** 双击快进/快退的涟漪：放大淡出 */
@Composable
private fun SeekRipple(forward: Boolean) {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(durationMillis = 600))
    }
    Box(
        modifier = Modifier
            .size(96.dp)
            .graphicsLayer(
                scaleX = 0.7f + 0.5f * progress.value,
                scaleY = 0.7f + 0.5f * progress.value,
                alpha = (1f - progress.value * 1.2f).coerceAtLeast(0f)
            )
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(
                imageVector = if (forward) Icons.Filled.Forward10 else Icons.Filled.Replay10,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(30.dp)
            )
            Text(
                text = "$SKIP_SECONDS 秒",
                color = Color.White,
                style = MaterialTheme.typography.labelMedium
            )
        }
    }
}

/** 竖直拖动时的亮度 / 音量指示条 */
@Composable
private fun VerticalLevelIndicator(
    icon: ImageVector,
    level: Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 14.dp, vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
        Text(
            text = "${(level * 100).roundToInt()}%",
            color = Color.White,
            style = MaterialTheme.typography.labelMedium
        )
        Box(
            modifier = Modifier
                .width(4.dp)
                .height(80.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.25f))
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(level.coerceIn(0f, 1f))
                        .background(Color.White)
                )
            }
        }
    }
}

@Composable
private fun AudioStageOverlay(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.10f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.Headphones,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.8f),
                modifier = Modifier.size(44.dp)
            )
        }
        Text(
            text = "音频模式",
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelLarge
        )
    }
}

@Composable
private fun StageSurface(
    sessionPlayer: Player?,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                // 换曲、播完后重新 prepare 都会 reset，默认亮出黑色快门，保留上一帧就不闪
                setKeepContentOnPlayerReset(true)
                // 全屏跟练时保持屏幕常亮
                keepScreenOn = true
            }
        },
        update = { view ->
            // setPlayer 会先解绑再重绑 Surface，重组一次就闪一下，只在实例真变了时才设
            if (view.player !== sessionPlayer) {
                view.player = sessionPlayer
            }
        },
        onRelease = { view ->
            view.player = null
            view.keepScreenOn = false
        },
        modifier = modifier.fillMaxSize()
    )
}

private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
