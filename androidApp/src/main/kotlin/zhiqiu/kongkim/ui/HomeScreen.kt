@file:OptIn(ExperimentalMaterial3Api::class)

package zhiqiu.kongkim.ui

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.SelfImprovement
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VideocamOff
import androidx.compose.material.icons.outlined.Alarm
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.Player
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.flow.StateFlow
import zhiqiu.kongkim.media.MediaTrack

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val viewModel: PlayerViewModel = viewModel()
    val player by viewModel.player.collectAsStateWithLifecycle()
    val alarm by viewModel.alarm.collectAsStateWithLifecycle()

    val lifecycleOwner = LocalLifecycleOwner.current
    var showTimePicker by remember { mutableStateOf(false) }
    var notificationsEnabled by remember { mutableStateOf(true) }
    var exactAlarmsAllowed by remember { mutableStateOf(viewModel.canScheduleExactAlarms()) }

    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> notificationsEnabled = granted }

    LaunchedEffect(Unit) {
        viewModel.warmUpService()
        val granted = NotificationManagerCompat.from(context).areNotificationsEnabled()
        notificationsEnabled = granted
        if (!granted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    viewModel.setScreenOn(true)
                    exactAlarmsAllowed = viewModel.canScheduleExactAlarms()
                    notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
                }
                Lifecycle.Event.ON_PAUSE -> viewModel.setScreenOn(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(containerColor = MaterialTheme.colorScheme.background) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            AppHeader(isPlaying = player.isPlaying)

            NowPlayingCard(
                state = player,
                position = viewModel.position,
                sessionPlayer = viewModel.sessionPlayer,
                onTogglePlay = viewModel::togglePlay,
                onSkipBackward = viewModel::skipBackward,
                onSkipForward = viewModel::skipForward,
                onSeek = viewModel::seekTo,
                onRestart = viewModel::restart,
                onSpeedChange = viewModel::setSpeed,
                onRetry = viewModel::retry,
            )

            SectionHeader("练习设置")
            SectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        MediaTrack.entries.forEach { option ->
                            VersionTile(
                                track = option,
                                selected = option == player.track,
                                onClick = { viewModel.setTrack(option) }
                            )
                        }
                    }
                    SwitchRow(
                        icon = if (player.audioOnly) {
                            Icons.Filled.VideocamOff
                        } else {
                            Icons.Filled.Videocam
                        },
                        title = "仅音频播放",
                        subtitle = "关闭画面更省电，息屏也能继续跟练",
                        checked = player.audioOnly,
                        onCheckedChange = viewModel::setAudioOnly
                    )
                }
            }

            SectionHeader("每日提醒")
            SectionCard {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    SwitchRow(
                        icon = Icons.Outlined.Alarm,
                        title = "每日提醒",
                        subtitle = if (alarm.enabled) {
                            "已开启 · ${alarm.track.label}"
                        } else {
                            "关闭"
                        },
                        checked = alarm.enabled,
                        onCheckedChange = { enabled ->
                            if (enabled && !viewModel.canScheduleExactAlarms()) {
                                viewModel.openExactAlarmSettings()
                            }
                            exactAlarmsAllowed = viewModel.canScheduleExactAlarms()
                            viewModel.setAlarmEnabled(enabled)
                        }
                    )

                    TimeRow(
                        hour = alarm.hour,
                        minute = alarm.minute,
                        onClick = { showTimePicker = true }
                    )

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "提醒版本",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            MediaTrack.entries.forEach { option ->
                                FilterChip(
                                    selected = option == alarm.track,
                                    onClick = { viewModel.setAlarmTrack(option) },
                                    label = { Text(option.label) }
                                )
                            }
                        }
                    }

                    Text(
                        text = "到点由本应用直接播放，不依赖系统铃声",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (alarm.enabled && !exactAlarmsAllowed) {
                HintCard(
                    icon = Icons.Outlined.Warning,
                    text = "系统还未允许精确闹钟，提醒可能延迟",
                    actionLabel = "去设置",
                    onAction = viewModel::openExactAlarmSettings,
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            }

            if (!notificationsEnabled) {
                HintCard(
                    icon = Icons.Outlined.Notifications,
                    text = "通知权限关闭时，锁屏与通知栏的播放控制可能不可用",
                    actionLabel = null,
                    onAction = null,
                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(24.dp))
        }
    }

    if (showTimePicker) {
        AlarmTimeDialog(
            hour = alarm.hour,
            minute = alarm.minute,
            onDismiss = { showTimePicker = false },
            onConfirm = { hour, minute ->
                viewModel.setAlarmTime(hour, minute)
                showTimePicker = false
            }
        )
    }
}

@Composable
private fun AppHeader(isPlaying: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Filled.SelfImprovement,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("金刚功", style = MaterialTheme.typography.titleLarge)
            Text(
                text = "跟着口令，练完全程",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        StatusPill(isPlaying = isPlaying)
    }
}

@Composable
private fun StatusPill(isPlaying: Boolean) {
    val container = if (isPlaying) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (isPlaying) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(shape = CircleShape, color = container, modifier = Modifier) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (isPlaying) MaterialTheme.colorScheme.primary else content)
            )
            Text(
                text = if (isPlaying) "练习中" else "待机",
                style = MaterialTheme.typography.labelLarge,
                color = content
            )
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Box(
            modifier = Modifier
                .height(1.dp)
                .weight(1f)
                .background(MaterialTheme.colorScheme.outlineVariant)
        )
    }
}

@Composable
private fun SectionCard(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.fillMaxWidth()
    ) { content() }
}

@Composable
private fun NowPlayingCard(
    state: PlayerUiState,
    position: StateFlow<Long>,
    sessionPlayer: Player?,
    onTogglePlay: () -> Unit,
    onSkipBackward: () -> Unit,
    onSkipForward: () -> Unit,
    onSeek: (Long) -> Unit,
    onRestart: () -> Unit,
    onSpeedChange: (Float) -> Unit,
    onRetry: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            MediaStage(
                state = state,
                sessionPlayer = sessionPlayer,
                onTogglePlay = onTogglePlay
            )
            Column(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = state.track.label,
                            style = MaterialTheme.typography.titleLarge
                        )
                        Text(
                            text = "${if (state.audioOnly) "音频" else "画面"}模式 · " + when {
                                state.errorMessage != null -> "播放异常"
                                state.isPlaying -> "正在播放"
                                else -> "已暂停"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = PlayerViewModel.formatTime(state.durationMs, state.durationMs),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                state.errorMessage?.let { message ->
                    HintCard(
                        icon = Icons.Outlined.Warning,
                        text = message,
                        actionLabel = "重试",
                        onAction = onRetry,
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    )
                }

                PositionScrubber(
                    position = position,
                    durationMs = state.durationMs,
                    onSeek = onSeek
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SkipButton(
                        icon = Icons.Filled.Replay10,
                        description = "后退 ${SKIP_SECONDS} 秒",
                        onClick = onSkipBackward
                    )
                    FilledIconButton(
                        onClick = onTogglePlay,
                        modifier = Modifier.size(76.dp),
                        shape = CircleShape
                    ) {
                        Icon(
                            imageVector = if (state.isPlaying) {
                                Icons.Filled.Pause
                            } else {
                                Icons.Filled.PlayArrow
                            },
                            contentDescription = if (state.isPlaying) "暂停" else "播放",
                            modifier = Modifier.size(38.dp)
                        )
                    }
                    SkipButton(
                        icon = Icons.Filled.Forward10,
                        description = "快进 ${SKIP_SECONDS} 秒",
                        onClick = onSkipForward
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "倍速",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.width(44.dp)
                    )
                    PlayerViewModel.SPEED_OPTIONS.forEach { option ->
                        FilterChip(
                            selected = option == state.speed,
                            onClick = { onSpeedChange(option) },
                            label = { Text(PlayerViewModel.formatSpeed(option)) }
                        )
                    }
                }

                TextButton(
                    onClick = onRestart,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                ) {
                    Icon(
                        imageVector = Icons.Filled.RestartAlt,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("从头播放")
                }
            }
        }
    }
}

@Composable
private fun MediaStage(
    state: PlayerUiState,
    sessionPlayer: Player?,
    onTogglePlay: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
            .background(
                if (state.audioOnly) {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                } else {
                    Color.Black
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        if (state.audioOnly) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.30f),
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(92.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceContainerLowest),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Headphones,
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        text = "音频模式",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                        resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    }
                },
                update = { view ->
                    view.player = sessionPlayer
                    view.setOnClickListener { onTogglePlay() }
                },
                onRelease = { view ->
                    // 切到音频模式时解绑 Surface，别把解码输出留在内存里
                    view.player = null
                },
                modifier = Modifier.fillMaxSize()
            )
            if (sessionPlayer == null) {
                Text(
                    text = "正在连接播放服务…",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SkipButton(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        FilledTonalIconButton(
            onClick = onClick,
            modifier = Modifier.size(58.dp),
            shape = CircleShape
        ) {
            Icon(
                imageVector = icon,
                contentDescription = description,
                modifier = Modifier.size(28.dp)
            )
        }
        Text(
            text = "$SKIP_SECONDS 秒",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun PositionScrubber(
    position: StateFlow<Long>,
    durationMs: Long,
    onSeek: (Long) -> Unit,
) {
    if (durationMs <= 0L) {
        LinearProgressIndicator(
            progress = { 0f },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
        )
        return
    }
    // 进度单独订阅：每秒四次的跳动只重组这一块，不再牵动整张卡片
    val positionMs by position.collectAsStateWithLifecycle()
    var dragPosition by remember { mutableStateOf<Float?>(null) }
    val shown = (dragPosition ?: positionMs.toFloat()).coerceIn(0f, durationMs.toFloat())
    Slider(
        value = shown,
        onValueChange = { dragPosition = it },
        onValueChangeFinished = {
            dragPosition?.let { onSeek(it.toLong()) }
            dragPosition = null
        },
        valueRange = 0f..durationMs.toFloat(),
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = PlayerViewModel.formatTime(positionMs, durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "-" + PlayerViewModel.formatTime(durationMs - positionMs, durationMs),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun RowScope.VersionTile(
    track: MediaTrack,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    val container = if (selected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val content = if (selected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Column(
        modifier = Modifier
            .weight(1f)
            .clip(shape)
            .background(container)
            .border(
                width = if (selected) 0.dp else 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant,
                shape = shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = track.label,
                style = MaterialTheme.typography.titleMedium,
                color = content,
                modifier = Modifier.weight(1f)
            )
            if (selected) {
                Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
        Text(
            text = if (selected) "当前版本" else "切换版本",
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun TimeRow(hour: Int, minute: Int, onClick: () -> Unit) {
    val shape = RoundedCornerShape(20.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "提醒时间",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "%02d:%02d".format(hour, minute),
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        Icon(
            imageVector = Icons.Filled.Schedule,
            contentDescription = "修改提醒时间",
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun HintCard(
    icon: ImageVector,
    text: String,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    containerColor: Color,
    contentColor: Color,
) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = containerColor,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = contentColor
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = text,
                style = MaterialTheme.typography.bodySmall,
                color = contentColor,
                modifier = Modifier.weight(1f)
            )
            if (actionLabel != null && onAction != null) {
                TextButton(
                    onClick = onAction,
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp)
                ) {
                    Text(actionLabel, color = contentColor)
                }
            }
        }
    }
}

@Composable
private fun AlarmTimeDialog(
    hour: Int,
    minute: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int, Int) -> Unit,
) {
    val state = rememberTimePickerState(initialHour = hour, initialMinute = minute, is24Hour = true)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提醒时间") },
        text = {
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                TimePicker(state = state)
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(state.hour, state.minute) }) { Text("确定") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}
