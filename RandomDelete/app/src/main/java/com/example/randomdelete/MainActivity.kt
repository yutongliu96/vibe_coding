package com.example.randomdelete

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.media.AudioAttributes
import android.media.SoundPool
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.exifinterface.media.ExifInterface
import android.location.Geocoder
import com.example.randomdelete.ui.theme.RandomDeleteTheme
import android.view.HapticFeedbackConstants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RandomDeleteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    RandomDeleteApp(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }
}

private data class PhotoItem(
    val uri: Uri,
    val dateTaken: Long?
)

private enum class ScreenState {
    Splash,
    Start,
    Swiping,
    Review,
    AllMemories
}

private const val APP_PREFS_NAME = "random_delete_prefs"
private const val PREF_KEY_SWIPE_MUTED = "swipe_muted"

@Composable
private fun RandomDeleteApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember(context) {
        context.getSharedPreferences(APP_PREFS_NAME, Context.MODE_PRIVATE)
    }

    var screenState by remember { mutableStateOf(ScreenState.Splash) }
    var allPhotos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var swipePhotos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    val deleteCandidates = remember { mutableStateListOf<PhotoItem>() }

    // 本轮计划浏览的图片数量，默认 10，最大 30
    var desiredCount by remember { mutableStateOf(10) }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    // 默认非静音；用户切换后持久化，直到下次再次切换
    var isSwipeMuted by remember { mutableStateOf(prefs.getBoolean(PREF_KEY_SWIPE_MUTED, false)) }

    // 启动时垃圾桶动画结束后进入 Start 页面
    LaunchedEffect(Unit) {
        kotlinx.coroutines.delay(1500)
        screenState = ScreenState.Start
    }

    // 权限 launcher
    val permission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_IMAGES
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            // 拿到权限后加载图片
            scope.launch {
                loadRandomPhotos(
                    context = context,
                    onStartLoading = { isLoading = true; errorMessage = null },
                    onFinish = { isLoading = false },
                    onLoaded = { list ->
                        allPhotos = list
                        if (list.isEmpty()) {
                            errorMessage = "相册中没有找到图片。"
                            screenState = ScreenState.Start
                        } else {
                            val shuffled = list.shuffled(Random(System.currentTimeMillis()))
                            val count = desiredCount.coerceIn(10, 30)
                            swipePhotos = shuffled.take(min(count, shuffled.size))
                            deleteCandidates.clear()
                            currentIndex = 0
                            screenState = ScreenState.Swiping
                        }
                    },
                    onError = { msg ->
                        errorMessage = msg
                        screenState = ScreenState.Start
                    }
                )
            }
        } else {
            errorMessage = "未授予读取相册权限，无法随机选择图片。"
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        when (screenState) {
            ScreenState.Splash -> {
                SplashScreen()
            }

            ScreenState.Start -> {
                StartScreen(
                    isLoading = isLoading,
                    errorMessage = errorMessage,
                    selectedCount = desiredCount,
                    onCountChange = { newCount ->
                        desiredCount = newCount.coerceIn(10, 30)
                    },
                    onStartClick = {
                        // 如果已经有权限就直接加载，否则请求权限
                        if (context.checkSelfPermission(permission) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            scope.launch {
                                loadRandomPhotos(
                                    context = context,
                                    onStartLoading = { isLoading = true; errorMessage = null },
                                    onFinish = { isLoading = false },
                                    onLoaded = { list ->
                                        allPhotos = list
                                        if (list.isEmpty()) {
                                            errorMessage = "相册中没有找到图片。"
                                        } else {
                                            val shuffled = list.shuffled(Random(System.currentTimeMillis()))
                                            val count = desiredCount.coerceIn(10, 30)
                                            swipePhotos = shuffled.take(min(count, shuffled.size))
                                            deleteCandidates.clear()
                                            currentIndex = 0
                                            screenState = ScreenState.Swiping
                                        }
                                    },
                                    onError = { msg ->
                                        errorMessage = msg
                                    }
                                )
                            }
                        } else {
                            permissionLauncher.launch(permission)
                        }
                    }
                )
            }

            ScreenState.Swiping -> {
                if (swipePhotos.isEmpty()) {
                    // 防御：没有照片时回到 Start
                    screenState = ScreenState.Start
                } else {
                    fun nextIdx(from: Int): Int? =
                        ((from + 1)..swipePhotos.lastIndex).firstOrNull { idx ->
                            val item = swipePhotos[idx]
                            !deleteCandidates.contains(item)
                        }
                    fun prevIdx(from: Int): Int? =
                        (from - 1 downTo 0).firstOrNull { idx ->
                            val item = swipePhotos[idx]
                            !deleteCandidates.contains(item)
                        }

                    SwipeScreen(
                        photos = swipePhotos,
                        currentIndex = currentIndex,
                        deleteSet = deleteCandidates.toSet(),
                        deleteCount = deleteCandidates.size,
                        isMuted = isSwipeMuted,
                        onMutedChange = { muted ->
                            isSwipeMuted = muted
                            prefs.edit().putBoolean(PREF_KEY_SWIPE_MUTED, muted).apply()
                        },
                        onDeleteCurrent = {
                            val current = swipePhotos[currentIndex]
                            if (!deleteCandidates.contains(current)) {
                                deleteCandidates.add(current)
                            }
                            val next = nextIdx(currentIndex)
                            if (next != null) {
                                currentIndex = next
                            } else {
                                screenState = if (deleteCandidates.isNotEmpty()) ScreenState.Review
                                else ScreenState.AllMemories
                            }
                        },
                        onNext = {
                            val next = nextIdx(currentIndex)
                            if (next != null) {
                                currentIndex = next
                            } else {
                                screenState = if (deleteCandidates.isNotEmpty()) ScreenState.Review
                                else ScreenState.AllMemories
                            }
                        },
                        onPrev = {
                            val prev = prevIdx(currentIndex)
                            if (prev != null) {
                                currentIndex = prev
                            }
                        },
                        onBackToStart = {
                            screenState = ScreenState.Start
                        }
                    )
                }
            }

            ScreenState.Review -> {
                ReviewScreen(
                    candidates = deleteCandidates,
                    onDeleteFinished = {
                        screenState = ScreenState.Start
                        deleteCandidates.clear()
                        swipePhotos = emptyList()
                        allPhotos = emptyList()
                        currentIndex = 0
                        errorMessage = null
                    }
                )
            }

            ScreenState.AllMemories -> {
                AllBeautifulMemoriesScreen(
                    onBackToStart = {
                        screenState = ScreenState.Start
                        deleteCandidates.clear()
                        swipePhotos = emptyList()
                        allPhotos = emptyList()
                        currentIndex = 0
                        errorMessage = null
                    }
                )
            }
        }
    }
}

/**
 * 启动页：垃圾桶简笔画 + 轻微缩放动画
 */
@Composable
private fun SplashScreen() {
    val scale by animateFloatAsState(
        targetValue = 1.1f,
        animationSpec = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
        label = "splashScale"
    )
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.icon),
                contentDescription = "app icon",
                modifier = Modifier
                    .size(160.dp)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    },
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Random Delete",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun TrashCanSketch(
    canvasSize: Dp,
    modifier: Modifier = Modifier
) {
    Canvas(
        modifier = modifier
            .size(canvasSize)
    ) {
        // Canvas 内部使用实际像素尺寸（DrawScope.size）
        val w = this.size.width
        val h = this.size.height
        val strokeWidth = 6f

        val bodyTop = h * 0.3f
        val bodyBottom = h * 0.85f
        val bodyLeft = w * 0.25f
        val bodyRight = w * 0.75f

        // 垃圾桶身体
        drawLine(
            color = Color.DarkGray,
            start = Offset(bodyLeft, bodyTop),
            end = Offset(bodyLeft * 0.9f, bodyBottom),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.DarkGray,
            start = Offset(bodyRight, bodyTop),
            end = Offset(bodyRight * 1.1f, bodyBottom),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.DarkGray,
            start = Offset(bodyLeft * 0.9f, bodyBottom),
            end = Offset(bodyRight * 1.1f, bodyBottom),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // 垃圾桶盖子
        val lidWidth = (bodyRight - bodyLeft) * 1.2f
        val lidLeft = (w - lidWidth) / 2f
        val lidRight = lidLeft + lidWidth
        val lidTop = h * 0.18f
        val lidBottom = h * 0.24f

        drawRoundRect(
            color = Color.DarkGray,
            topLeft = Offset(lidLeft, lidTop),
            size = androidx.compose.ui.geometry.Size(lidWidth, lidBottom - lidTop),
            style = Stroke(width = strokeWidth)
        )

        // 把手
        val handleWidth = lidWidth * 0.25f
        val handleLeft = (w - handleWidth) / 2f
        val handleRight = handleLeft + handleWidth
        val handleTop = h * 0.12f
        val handleBottom = h * 0.18f
        drawLine(
            color = Color.DarkGray,
            start = Offset(handleLeft, handleBottom),
            end = Offset(handleLeft, handleTop),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.DarkGray,
            start = Offset(handleRight, handleBottom),
            end = Offset(handleRight, handleTop),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )
        drawLine(
            color = Color.DarkGray,
            start = Offset(handleLeft, handleTop),
            end = Offset(handleRight, handleTop),
            strokeWidth = strokeWidth,
            cap = StrokeCap.Round
        )

        // 垃圾桶竖线
        val lineCount = 3
        repeat(lineCount) { i ->
            val x = bodyLeft + (bodyRight - bodyLeft) / (lineCount + 1) * (i + 1)
            drawLine(
                color = Color.LightGray,
                start = Offset(x, bodyTop * 1.05f),
                end = Offset(x, bodyBottom * 0.98f),
                strokeWidth = strokeWidth * 0.7f,
                cap = StrokeCap.Round
            )
        }
    }
}

@Composable
private fun StartScreen(
    isLoading: Boolean,
    errorMessage: String?,
    selectedCount: Int,
    onCountChange: (Int) -> Unit,
    onStartClick: () -> Unit
) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp)
        ) {
            Image(
                painter = painterResource(id = R.drawable.icon),
                contentDescription = "app icon",
                modifier = Modifier.size(120.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(24.dp))

            // 这次要看几张图？ + 滚轮样式选择器（10～30，循环）
            Text(
                text = "这次要看几张图？",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(12.dp))

            val minCount = 10
            val maxCount = 30
            fun inc(value: Int): Int = if (value >= maxCount) minCount else value + 1
            fun dec(value: Int): Int = if (value <= minCount) maxCount else value - 1

            val haptic = LocalHapticFeedback.current

            // 每一行在屏幕上的高度，用于将像素位移换算成 item 偏移量
            val density = LocalDensity.current
            val itemHeightPx = with(density) { 36.dp.toPx() }

            // 滚轮偏移（单位：item 高度），0 表示当前 selectedCount 在正中心
            var wheelOffset by remember { mutableStateOf(0f) }

            // 使用 scrollable 提供的惯性滚动行为
            val scrollState = rememberScrollableState { deltaPx ->
                // scrollable 的 delta 是“内容移动”距离，这里向上滑时 delta 为负
                val deltaItems = deltaPx / itemHeightPx
                wheelOffset += deltaItems

                // 每跨过 0.5 个 item，就切换一次数字并重置偏移，形成循环滚动
                while (wheelOffset <= -0.5f) {
                    val newValue = inc(selectedCount)
                    onCountChange(newValue)
                    wheelOffset += 1f
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                    )
                }
                while (wheelOffset >= 0.5f) {
                    val newValue = dec(selectedCount)
                    onCountChange(newValue)
                    wheelOffset -= 1f
                    haptic.performHapticFeedback(
                        androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove
                    )
                }

                // 告诉 scrollable 实际消费了多少像素
                deltaPx
            }

            Box(
                modifier = Modifier
                    .width(160.dp)
                    .border(
                        width = 1.dp,
                        color = Color.LightGray,
                        shape = RoundedCornerShape(16.dp)
                    )
                    .padding(vertical = 16.dp, horizontal = 12.dp)
                    .scrollable(
                        state = scrollState,
                        orientation = Orientation.Vertical,
                        flingBehavior = ScrollableDefaults.flingBehavior(),
                        enabled = true
                    )
            ) {
                // 显示多个可见项，形成 3D 滚轮效果
                val visibleCount = 7 // 中间 + 上下各 3
                val half = visibleCount / 2
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    for (i in -half..half) {
                        val position = i.toFloat()
                        // 当前项相对于中心的距离（加上滚轮偏移）
                        val distanceFromCenter = position + wheelOffset
                        val absDistance = kotlin.math.abs(distanceFromCenter)

                        // 缩放 + 透明度 + 轻微 3D 旋转
                        val scale = 1f - 0.12f * absDistance.coerceIn(0f, 3f)
                        val alpha = 1f - 0.35f * absDistance.coerceIn(0f, 3f)
                        val rotationX = 18f * distanceFromCenter

                        // 以 selectedCount 为中心，向上/向下依次递增/递减，形成循环
                        val value = when {
                            i == 0 -> selectedCount
                            i > 0 -> {
                                var v = selectedCount
                                repeat(i) { v = inc(v) }
                                v
                            }
                            else -> {
                                var v = selectedCount
                                repeat(-i) { v = dec(v) }
                                v
                            }
                        }

                        Box(
                            modifier = Modifier
                                .height(32.dp)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = value.toString(),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal,
                                color = MaterialTheme.colorScheme.onBackground.copy(alpha = alpha),
                                modifier = Modifier.graphicsLayer {
                                    this.scaleX = scale
                                    this.scaleY = scale
                                    this.rotationX = rotationX
                                    this.transformOrigin = TransformOrigin(0.5f, 0.5f)
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onStartClick,
                enabled = !isLoading
            ) {
                Text(text = if (isLoading) "读取中..." else "Start")
            }
            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = errorMessage,
                    color = MaterialTheme.colorScheme.error,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

private enum class DragAxis { Horizontal, Vertical }

private class SwipeWhooshPlayer(context: Context) {
    private val soundPool: SoundPool
    private var soundId: Int = 0
    private var loaded = false

    init {
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        soundPool = SoundPool.Builder()
            .setMaxStreams(2)
            .setAudioAttributes(attrs)
            .build()
        soundPool.setOnLoadCompleteListener { _, sampleId, status ->
            loaded = status == 0 && sampleId == soundId
        }
        soundId = soundPool.load(context, R.raw.flick_whoosh, 1)
    }

    fun play(isLeft: Boolean) {
        if (!loaded || soundId == 0) return
        val (leftVolume, rightVolume) = if (isLeft) {
            1.0f to 0.72f
        } else {
            0.72f to 1.0f
        }
        soundPool.play(soundId, leftVolume, rightVolume, 1, 0, 1.0f)
    }

    fun release() {
        soundPool.release()
    }
}

/**
 * 浏览随机照片：
 * - 左/右滑：标记删除并进入下一张（最后一张则进入删除页）
 * - 上滑：下一张（最后一张则进入删除页）
 * - 下滑：上一张未标记删除的照片
 */
@Composable
private fun SwipeScreen(
    photos: List<PhotoItem>,
    currentIndex: Int,
    deleteSet: Set<PhotoItem>,
    deleteCount: Int,
    isMuted: Boolean,
    onMutedChange: (Boolean) -> Unit,
    onDeleteCurrent: () -> Unit,
    onNext: () -> Unit,
    onPrev: () -> Unit,
    onBackToStart: () -> Unit
) {
    val uiContext = LocalContext.current
    // 每张图片独立位移状态，切换时重置
    var offsetX by remember(currentIndex) { mutableStateOf(0f) }
    var offsetY by remember(currentIndex) { mutableStateOf(0f) }
    var dragWidth by remember { mutableStateOf(1f) }
    var dragHeight by remember { mutableStateOf(1f) }
    var deleteThresholdHapticSent by remember(currentIndex) { mutableStateOf(false) }
    var hideCurrentPhotoDuringExit by remember(currentIndex) { mutableStateOf(false) }
    val view = LocalView.current
    val whooshPlayer = remember { SwipeWhooshPlayer(uiContext) }
    val swipeScope = rememberCoroutineScope()
    DisposableEffect(Unit) {
        onDispose { whooshPlayer.release() }
    }

    val alpha = remember(offsetX, offsetY, dragWidth, dragHeight) {
        val maxX = dragWidth / 2f
        val maxY = dragHeight / 2f
        val progressX = if (maxX > 0f) (kotlin.math.abs(offsetX) / maxX).coerceIn(0f, 1f) else 0f
        val progressY = if (maxY > 0f) (kotlin.math.abs(offsetY) / maxY).coerceIn(0f, 1f) else 0f
        val progress = kotlin.math.max(progressX, progressY)
        1f - 0.7f * progress
    }

    val nextAlpha = remember(offsetX, offsetY, dragWidth, dragHeight) {
        val maxX = dragWidth / 2f
        val maxY = dragHeight / 2f
        val progressX = if (maxX > 0f) (kotlin.math.abs(offsetX) / maxX).coerceIn(0f, 1f) else 0f
        val progressY = if (maxY > 0f) (kotlin.math.abs(offsetY) / maxY).coerceIn(0f, 1f) else 0f
        val progress = kotlin.math.max(progressX, progressY)
        0.9f * progress
    }

    val prevAlpha = remember(offsetX, offsetY, dragWidth, dragHeight) {
        val maxX = dragWidth / 2f
        val maxY = dragHeight / 2f
        val progressX = if (maxX > 0f) (kotlin.math.abs(offsetX) / maxX).coerceIn(0f, 1f) else 0f
        val progressY = if (maxY > 0f) (kotlin.math.abs(offsetY) / maxY).coerceIn(0f, 1f) else 0f
        val progress = kotlin.math.max(progressX, progressY)
        0.9f * progress
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 右上角静音按钮（喇叭形状，用文本符号表示）
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { onMutedChange(!isMuted) }) {
                    Text(
                        text = if (isMuted) "🔇" else "🔈"
                    )
                }
            }

            Text(
                text = "第 ${currentIndex + 1} / ${photos.size} 张",
                style = MaterialTheme.typography.titleMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "预备删除：$deleteCount 张",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(16.dp))

            val photo = photos[currentIndex]
            val nextIndex = ((currentIndex + 1)..photos.lastIndex).firstOrNull { idx ->
                val item = photos[idx]
                !deleteSet.contains(item)
            }
            val hasNext = nextIndex != null
            val nextPhoto = nextIndex?.let { photos[it] }
            val prevIndex = (currentIndex - 1 downTo 0).firstOrNull { idx ->
                val item = photos[idx]
                !deleteSet.contains(item)
            }
            val prevPhoto = prevIndex?.let { photos[it] }

            // 读取当前图片的拍摄地点和更精确的拍摄时间（如果有 EXIF 或文件名中带时间）
            val context = LocalContext.current
            var locationText by remember(photo.uri) { mutableStateOf<String?>(null) }
            var displayTimeMillis by remember(photo.uri) { mutableStateOf<Long?>(photo.dateTaken) }
            var timeLabel by remember(photo.uri) { mutableStateOf("时间") }

            LaunchedEffect(photo.uri) {
                val (timeFromExif, locationFromExif) = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(photo.uri)?.use { input ->
                            val exif = ExifInterface(input)

                            // 1. 拍摄时间（优先 EXIF）
                            val exifTimeString =
                                exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                                    ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                            val timeMillis = exifTimeString?.let { dt ->
                                try {
                                    val fmt = SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US)
                                    fmt.parse(dt)?.time
                                } catch (e: Exception) {
                                    null
                                }
                            }

                            // 2. 拍摄地点（城市 + 国家）
                            val latLong = FloatArray(2)
                            val location = if (exif.getLatLong(latLong)) {
                                val geocoder = Geocoder(context, Locale.getDefault())
                                val addresses = geocoder.getFromLocation(
                                    latLong[0].toDouble(),
                                    latLong[1].toDouble(),
                                    1
                                )
                                val address = addresses?.firstOrNull()
                                if (address != null) {
                                    val city = address.locality
                                        ?: address.subAdminArea
                                        ?: address.adminArea
                                    val country = address.countryName
                                    when {
                                        city != null && country != null -> "$city, $country"
                                        country != null -> country
                                        city != null -> city
                                        else -> null
                                    }
                                } else {
                                    null
                                }
                            } else {
                                null
                            }

                            Pair(timeMillis, location)
                        }
                    }.getOrNull() ?: Pair(null, null)
                }

                // 若 EXIF 有“合理”的时间，则覆盖 MediaStore 的时间；
                // 否则对 web 下载/拷贝类图片，继续使用导入时间（MediaStore 的时间）
                if (timeFromExif != null) {
                    val now = System.currentTimeMillis()
                    val lowerBound = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                        .parse("1990-01-01")?.time ?: 0L
                    val upperBound = now + 7L * 24 * 60 * 60 * 1000 // 稍微允许比当前时间晚一点
                    if (timeFromExif in lowerBound..upperBound) {
                        displayTimeMillis = timeFromExif
                        timeLabel = "拍摄时间"
                    }
                }

                // 2. 如果 EXIF 没有可靠时间，再尝试从文件名中解析时间（例如 20201231_235959.jpg）
                if (timeFromExif == null || timeLabel != "拍摄时间") {
                    val name = photo.uri.lastPathSegment ?: ""
                    val nameTime = runCatching {
                        val candidates = listOf(
                            "yyyyMMdd_HHmmss",
                            "yyyyMMddHHmmss",
                            "yyyy-MM-dd_HH-mm-ss",
                            "yyyy-MM-dd HH-mm-ss",
                            "yyyy-MM-dd HH.mm.ss",
                            "yyyy_MM_dd_HH_mm_ss",
                            "yyyyMMdd"
                        )
                        val firstDigits = Regex("(\\d{8,14})").find(name)?.value
                        if (firstDigits != null) {
                            for (pattern in candidates) {
                                try {
                                    val fmt = SimpleDateFormat(pattern, Locale.US)
                                    fmt.isLenient = false
                                    val parsed = fmt.parse(firstDigits) ?: continue
                                    return@runCatching parsed.time
                                } catch (_: Exception) {
                                    // 尝试下一个 pattern
                                }
                            }
                        }
                        null
                    }.getOrNull()

                    if (nameTime != null) {
                        val now = System.currentTimeMillis()
                        val lowerBound = SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .parse("1990-01-01")?.time ?: 0L
                        val upperBound = now + 7L * 24 * 60 * 60 * 1000
                        if (nameTime in lowerBound..upperBound) {
                            displayTimeMillis = nameTime
                            timeLabel = "拍摄时间"
                        }
                    }
                }

                // 若既没有 EXIF 时间，也未从文件名解析到时间，则回退到导入时间
                if (displayTimeMillis == null && photo.dateTaken != null) {
                    displayTimeMillis = photo.dateTaken
                    timeLabel = "导入时间"
                }
                locationText = locationFromExif
            }

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                // 预渲染下一张作为底图（上滑/左右滑时渐显）
                if (nextPhoto != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = 1.dp,
                                color = Color.LightGray,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = nextPhoto.uri,
                            contentDescription = "next photo",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentScale = ContentScale.Fit,
                            alpha = if (offsetY < 0f || kotlin.math.abs(offsetX) > kotlin.math.abs(offsetY)) nextAlpha else 0f
                        )
                    }
                }

                // 预渲染上一张未标记删除的照片（下滑时渐显）
                if (prevPhoto != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .border(
                                width = 1.dp,
                                color = Color.LightGray,
                                shape = RoundedCornerShape(16.dp)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = prevPhoto.uri,
                            contentDescription = "prev photo",
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(8.dp),
                            contentScale = ContentScale.Fit,
                            alpha = if (offsetY > 0f) prevAlpha else 0f
                        )
                    }
                }

                // 当前图片：平移 + 透明度变化
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = offsetX
                            translationY = offsetY
                            this.alpha = if (hideCurrentPhotoDuringExit) 0f else alpha
                        }
                        .border(
                            width = 1.dp,
                            color = Color.LightGray,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .pointerInput(photo.uri) {
                            // 手势：左右=删除，上=下一张，下=上一张（跳过已标记删除），锁定单一方向
                            var dragAxis: DragAxis? = null
                            detectDragGestures(
                                onDragStart = { _ ->
                                    val heightPx = size.height.toFloat().coerceAtLeast(1f)
                                    val widthPx = size.width.toFloat().coerceAtLeast(1f)
                                    dragHeight = heightPx
                                    dragWidth = widthPx
                                    deleteThresholdHapticSent = false
                                    hideCurrentPhotoDuringExit = false
                                    dragAxis = null
                                    offsetX = 0f
                                    offsetY = 0f
                                },
                                onDragCancel = {
                                    deleteThresholdHapticSent = false
                                    hideCurrentPhotoDuringExit = false
                                    offsetX = 0f
                                    offsetY = 0f
                                    dragAxis = null
                                },
                                onDragEnd = {
                                    val heightPx = dragHeight.coerceAtLeast(1f)
                                    val widthPx = dragWidth.coerceAtLeast(1f)
                                    val absX = kotlin.math.abs(offsetX)
                                    val absY = kotlin.math.abs(offsetY)
                                    val horizontal = when (dragAxis) {
                                        DragAxis.Horizontal -> true
                                        DragAxis.Vertical -> false
                                        null -> absX > absY
                                    }
                                    val primaryDist = if (horizontal) absX else absY
                                    val threshold = (if (horizontal) widthPx else heightPx) * 0.20f

                                    if (primaryDist < threshold) {
                                        deleteThresholdHapticSent = false
                                        hideCurrentPhotoDuringExit = false
                                        offsetX = 0f
                                        offsetY = 0f
                                        dragAxis = null
                                        return@detectDragGestures
                                    }

                                    if (horizontal) {
                                        val deletingLastPhoto = !hasNext
                                        val direction = if (offsetX < 0f) -1f else 1f
                                        if (!isMuted) whooshPlayer.play(isLeft = offsetX < 0f)
                                        deleteThresholdHapticSent = false
                                        dragAxis = null
                                        if (deletingLastPhoto) {
                                            // 最后一张删除时：直接飞出并隐藏当前图，避免回弹停留。
                                            hideCurrentPhotoDuringExit = true
                                            offsetX = direction * dragWidth.coerceAtLeast(1f) * 1.25f
                                            offsetY = 0f
                                            if (!isMuted) {
                                                swipeScope.launch {
                                                    delay(170L)
                                                    onDeleteCurrent()
                                                }
                                            } else {
                                                onDeleteCurrent()
                                            }
                                        } else {
                                            hideCurrentPhotoDuringExit = false
                                            offsetX = 0f
                                            offsetY = 0f
                                            onDeleteCurrent()
                                        }
                                    } else {
                                        val isDown = offsetY > 0f
                                        deleteThresholdHapticSent = false
                                        hideCurrentPhotoDuringExit = false
                                        offsetX = 0f
                                        offsetY = 0f
                                        dragAxis = null
                                        if (isDown) onPrev() else onNext()
                                    }
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                if (dragAxis == null) {
                                    dragAxis = if (kotlin.math.abs(dragAmount.x) >= kotlin.math.abs(dragAmount.y))
                                        DragAxis.Horizontal else DragAxis.Vertical
                                }
                                if (dragAxis == DragAxis.Horizontal) {
                                    offsetX += dragAmount.x
                                    offsetY = 0f
                                    val deleteThreshold = dragWidth.coerceAtLeast(1f) * 0.20f
                                    if (!deleteThresholdHapticSent && kotlin.math.abs(offsetX) >= deleteThreshold) {
                                        // 临界点：确认删除阈值被越过时，触发一次强反馈
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        deleteThresholdHapticSent = true
                                    }
                                } else {
                                    offsetY += dragAmount.y
                                    offsetX = 0f
                                }
                            }
                        }
                        .pointerInput(photo.uri) {
                            // 不滑动状态下双击，使用系统相册/图片查看器打开这张图片
                            detectTapGestures(
                                onDoubleTap = {
                                    if (
                                        kotlin.math.abs(offsetY) < dragHeight / 10f &&
                                        kotlin.math.abs(offsetX) < dragWidth / 10f
                                    ) {
                                        openPhotoExternally(context, photo.uri)
                                    }
                                }
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                    ) {
                        AsyncImage(
                            model = photo.uri,
                            contentDescription = "photo",
                            modifier = Modifier.fillMaxSize(),
                            // 以完整尺寸展示（按比例缩放，可能有上下/左右留边）
                            contentScale = ContentScale.Fit
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            val dateText = remember(displayTimeMillis) {
                displayTimeMillis?.let {
                    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
                    fmt.format(Date(it))
                } ?: "无时间信息"
            }

            val prefix = when (timeLabel) {
                "拍摄时间" -> "拍摄时间"
                "导入时间" -> "导入时间"
                else -> "时间"
            }

            val infoText = if (locationText != null) {
                "$prefix：$dateText   拍摄地点：${locationText}"
            } else {
                "$prefix：$dateText"
            }

            Text(
                text = infoText,
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onBackToStart) {
                    Text("返回 Start")
                }
                Column(
                    horizontalAlignment = Alignment.End
                ) {
                    Text(
                        text = "上下滑=浏览",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = "左右滑=删除",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = "双击=相册中打开",
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}
/**
 * 复盘要删除的图片，点击勾选，点击 Delete 真正删除。
 */
@Composable
private fun ReviewScreen(
    candidates: List<PhotoItem>,
    onDeleteFinished: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val selected = remember(candidates) {
        mutableStateListOf<PhotoItem>().apply { addAll(candidates) }
    }
    var isDeleting by remember { mutableStateOf(false) }
    val allSelected = selected.size == candidates.size && candidates.isNotEmpty()
    var showGiveUp by remember { mutableStateOf(false) }
    var pendingDeleteUris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var pendingWriteGrantUris by remember { mutableStateOf<List<Uri>>(emptyList()) }

    fun finishRound() {
        isDeleting = false
        pendingDeleteUris = emptyList()
        pendingWriteGrantUris = emptyList()
        onDeleteFinished()
    }
    var launchTrashRequest: ((List<Uri>) -> Unit)? = null

    // 兜底：申请对目标图片的写权限后，再次尝试强制标记回收站
    val writeGrantLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode != Activity.RESULT_OK) {
            isDeleting = false
            return@rememberLauncherForActivityResult
        }

        val retryUris = pendingWriteGrantUris
        if (retryUris.isEmpty()) {
            isDeleting = false
            return@rememberLauncherForActivityResult
        }

        scope.launch {
            val stillFailed = markPhotosTrashedWithWriteAccess(context, retryUris)
            if (stillFailed.isEmpty()) {
                refreshGalleryForUris(context, retryUris)
                finishRound()
            } else {
                pendingWriteGrantUris = stillFailed
                isDeleting = false
            }
        }
    }

    // 系统确认：回收站请求 / 永久删除请求
    val deleteRequestLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val targetUris = pendingDeleteUris
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && targetUris.isNotEmpty()) {
                // 删除后状态校验：若仍未进入回收站，走写权限兜底再强制标记
                scope.launch {
                    val stillFailed = verifyPhotosInTrash(context, targetUris)
                    if (stillFailed.isEmpty()) {
                        refreshGalleryForUris(context, targetUris)
                        finishRound()
                    } else {
                        pendingWriteGrantUris = stillFailed
                        runCatching {
                            val pi = MediaStore.createWriteRequest(
                                context.contentResolver,
                                ArrayList(stillFailed)
                            )
                            writeGrantLauncher.launch(
                                androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender)
                                    .build()
                            )
                        }.onFailure {
                            isDeleting = false
                        }
                    }
                }
            } else {
                finishRound()
            }
        } else {
            // 用户取消时停留在当前页
            isDeleting = false
        }
    }
    launchTrashRequest = { uris ->
        if (uris.isEmpty()) {
            isDeleting = false
        } else {
            pendingDeleteUris = uris
            runCatching {
                val pi = MediaStore.createTrashRequest(
                    context.contentResolver,
                    ArrayList(uris),
                    /* isTrashed = */ true
                )
                deleteRequestLauncher.launch(
                    androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender)
                        .build()
                )
            }.onFailure {
                // 若 TrashRequest 发起失败，先申请写权限，再重试 TrashRequest
                pendingWriteGrantUris = uris
                runCatching {
                    val pi = MediaStore.createWriteRequest(
                        context.contentResolver,
                        ArrayList(uris)
                    )
                    writeGrantLauncher.launch(
                        androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender)
                            .build()
                    )
                }.onFailure {
                    isDeleting = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        if (showGiveUp) {
            // “想想还是算了吧” 页面，点击任意位置回到 Start
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        showGiveUp = false
                        onDeleteFinished()
                    },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "想想还是算了吧",
                        style = MaterialTheme.typography.headlineMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "轻触屏幕回到 Start。",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                Text(
                    text = "准备删除的图片（点击勾选/取消）：",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))

                // 全选 / 取消全选 按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Button(
                        onClick = {
                            if (allSelected) {
                                selected.clear()
                            } else {
                                selected.clear()
                                selected.addAll(candidates)
                            }
                        }
                    ) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                if (candidates.isEmpty()) {
                    Text(
                        text = "本轮没有选择删除任何图片。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        items(candidates, key = { it.uri.toString() }) { photo ->
                            val checked = selected.contains(photo)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                                    .clickable {
                                        if (checked) selected.remove(photo) else selected.add(photo)
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                AsyncImage(
                                    model = photo.uri,
                                    contentDescription = "candidate",
                                    modifier = Modifier
                                        .size(72.dp)
                                        .border(
                                            width = 1.dp,
                                            color = Color.LightGray,
                                            shape = RoundedCornerShape(8.dp)
                                        ),
                                    contentScale = ContentScale.Crop
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(
                                    modifier = Modifier.weight(1f)
                                ) {
                                    val reviewTimeText = remember(photo.dateTaken) {
                                        photo.dateTaken?.let {
                                            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                            "时间：${fmt.format(Date(it))}"
                                        } ?: "无时间信息"
                                    }
                                    Text(
                                        text = reviewTimeText,
                                        maxLines = 1
                                    )
                                }
                                Checkbox(
                                    checked = checked,
                                    onCheckedChange = {
                                        if (checked) selected.remove(photo) else selected.add(photo)
                                    }
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Button(
                        onClick = {
                            if (isDeleting) return@Button
                            showGiveUp = true
                        },
                        enabled = !isDeleting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("放弃删除")
                    }

                    Button(
                        onClick = {
                            if (isDeleting) return@Button
                            if (selected.isEmpty()) {
                                // 没有选择任何图片时，展示“想想还是算了吧”页面
                                showGiveUp = true
                                return@Button
                            }
                            isDeleting = true
                            val uris = selected.map { it.uri }

                            when {
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                                    // Android 11+ 官方回收站流程：始终通过系统 TrashRequest 标记为“最近删除”
                                    launchTrashRequest?.invoke(uris) ?: run {
                                        isDeleting = false
                                    }
                                }

                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                                    // Android 10 无官方“回收站”API，只能系统确认后永久删除
                                    val pi = MediaStore.createDeleteRequest(
                                        context.contentResolver,
                                        ArrayList(uris)
                                    )
                                    deleteRequestLauncher.launch(
                                        androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender)
                                            .build()
                                    )
                                }

                                else -> {
                                    // 更老的系统直接删除
                                    scope.launch {
                                        withContext(Dispatchers.IO) {
                                            selected.forEach { item ->
                                                runCatching {
                                                    context.contentResolver.delete(item.uri, null, null)
                                                }
                                            }
                                        }
                                        finishRound()
                                    }
                                }
                            }
                        },
                        enabled = !isDeleting,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = if (isDeleting) "删除中..." else "Delete（共 ${selected.size} 张）"
                        )
                    }
                }
            }
        }
    }
}

/**
 * 当本轮没有任何图片被标记为删除时，
 * 展示“都是美好的记忆！”的全屏提示，点击任意位置返回 Start。
 */
@Composable
private fun AllBeautifulMemoriesScreen(
    onBackToStart: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable { onBackToStart() }
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "都是美好的记忆！",
                style = MaterialTheme.typography.headlineMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "轻触屏幕回到 Start。",
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * 从系统相册中随机读取图片
 * 作为 suspend 函数，通过协程在 IO 线程执行查询。
 */
private suspend fun loadRandomPhotos(
    context: android.content.Context,
    onStartLoading: () -> Unit,
    onFinish: () -> Unit,
    onLoaded: (List<PhotoItem>) -> Unit,
    onError: (String) -> Unit
) {
    onStartLoading()
    try {
        val photos = withContext(Dispatchers.IO) {
            val list = mutableListOf<PhotoItem>()
            val collection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
                } else {
                    MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }

            // 同时读取 DATE_TAKEN / DATE_ADDED / DATE_MODIFIED，综合判断时间。
            // Android 10+ 额外读取 VOLUME_NAME，确保每张图使用其真实存储卷 URI。
            val projection =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DATE_MODIFIED,
                        MediaStore.Images.Media.VOLUME_NAME
                    )
                } else {
                    arrayOf(
                        MediaStore.Images.Media._ID,
                        MediaStore.Images.Media.DATE_TAKEN,
                        MediaStore.Images.Media.DATE_ADDED,
                        MediaStore.Images.Media.DATE_MODIFIED
                    )
                }

            context.contentResolver.query(
                collection,
                projection,
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateTakenColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                val dateModifiedColumn =
                    cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
                val volumeNameColumn =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        cursor.getColumnIndex(MediaStore.Images.Media.VOLUME_NAME)
                    } else {
                        -1
                    }
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val rawTaken = cursor.getLong(dateTakenColumn)        // 毫秒或 0
                    val rawAdded = cursor.getLong(dateAddedColumn)        // 秒或 0
                    val rawModified = cursor.getLong(dateModifiedColumn)  // 秒或 0

                    // 优先级：拍摄时间 > 修改时间 > 添加时间
                    val timeMillis: Long? = when {
                        rawTaken > 0L -> rawTaken
                        rawModified > 0L -> rawModified * 1000L
                        rawAdded > 0L -> rawAdded * 1000L
                        else -> null
                    }

                    val itemCollection = if (
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                        volumeNameColumn >= 0 &&
                        !cursor.isNull(volumeNameColumn)
                    ) {
                        val volumeName = cursor.getString(volumeNameColumn)
                        MediaStore.Images.Media.getContentUri(volumeName)
                    } else {
                        collection
                    }
                    val uri = ContentUris.withAppendedId(itemCollection, id)
                    list.add(PhotoItem(uri, timeMillis))
                }
            }
            list
        }
        onLoaded(photos)
    } catch (e: Exception) {
        onError("读取相册失败：${e.message ?: "未知错误"}")
    } finally {
        onFinish()
    }
}

private fun isPhotoInTrash(context: Context, uri: Uri): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    val queryArgs = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }
    return runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(MediaStore.MediaColumns.IS_TRASHED),
            queryArgs,
            null
        )?.use { cursor ->
            val idx = cursor.getColumnIndex(MediaStore.MediaColumns.IS_TRASHED)
            idx >= 0 && cursor.moveToFirst() && cursor.getInt(idx) == 1
        } ?: false
    }.getOrDefault(false)
}

private suspend fun verifyPhotosInTrash(
    context: Context,
    uris: List<Uri>
): List<Uri> = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext uris
    if (uris.isEmpty()) return@withContext emptyList()

    suspend fun unresolvedAfterRetry(target: List<Uri>): List<Uri> {
        var unresolvedLocal = target
        repeat(4) { attempt ->
            unresolvedLocal = unresolvedLocal.filterNot { isPhotoInTrash(context, it) }
            if (unresolvedLocal.isEmpty()) return unresolvedLocal
            if (attempt < 3) kotlinx.coroutines.delay(180)
        }
        return unresolvedLocal
    }

    var unresolved = unresolvedAfterRetry(uris)
    unresolved
}

private suspend fun markPhotosTrashedWithWriteAccess(
    context: Context,
    uris: List<Uri>
): List<Uri> = withContext(Dispatchers.IO) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext uris
    if (uris.isEmpty()) return@withContext emptyList()

    val values = ContentValues().apply {
        put(MediaStore.MediaColumns.IS_TRASHED, 1)
    }

    uris.forEach { uri ->
        runCatching {
            context.contentResolver.update(uri, values, null, null)
        }
    }

    verifyPhotosInTrash(context, uris)
}

private fun refreshGalleryForUris(context: Context, uris: List<Uri>) {
    uris.forEach { uri ->
        runCatching { context.contentResolver.notifyChange(uri, null) }
        runCatching {
            context.sendBroadcast(
                Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, uri)
            )
        }
    }
}

private fun openPhotoExternally(context: Context, uri: Uri) {
    val mime = context.contentResolver.getType(uri) ?: "image/*"
    val baseIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, mime)
        clipData = ClipData.newUri(context.contentResolver, "photo", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    // MIUI 上优先尝试系统相册，降低解析到错误图片的概率。
    val candidates = mutableListOf<Intent>()
    val manufacturer = Build.MANUFACTURER?.lowercase(Locale.ROOT).orEmpty()
    if (manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")) {
        candidates += Intent(baseIntent).apply { `package` = "com.miui.gallery" }
    }
    candidates += baseIntent
    candidates += Intent(Intent.ACTION_VIEW).apply {
        data = uri
        clipData = ClipData.newUri(context.contentResolver, "photo", uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    for (intent in candidates) {
        val launched = runCatching {
            if (intent.resolveActivity(context.packageManager) != null) {
                context.startActivity(intent)
                true
            } else {
                false
            }
        }.getOrDefault(false)
        if (launched) return
    }
}

@Preview(showBackground = true)
@Composable
private fun SplashPreview() {
    RandomDeleteTheme {
        SplashScreen()
    }
}

