package com.example.randomdelete

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import androidx.exifinterface.media.ExifInterface
import android.location.Geocoder
import com.example.randomdelete.ui.theme.RandomDeleteTheme
import android.view.SoundEffectConstants
import android.media.AudioManager
import android.media.ToneGenerator
import kotlinx.coroutines.Dispatchers
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

@Composable
private fun RandomDeleteApp(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var screenState by remember { mutableStateOf(ScreenState.Splash) }
    var allPhotos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var swipePhotos by remember { mutableStateOf<List<PhotoItem>>(emptyList()) }
    var currentIndex by remember { mutableStateOf(0) }
    val deleteCandidates = remember { mutableStateListOf<PhotoItem>() }

    var errorMessage by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

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
                            swipePhotos = shuffled.take(min(10, shuffled.size))
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
                                            swipePhotos = shuffled.take(min(10, shuffled.size))
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
                    SwipeScreen(
                        photos = swipePhotos,
                        currentIndex = currentIndex,
                        deleteCount = deleteCandidates.size,
                        onDecide = { isDelete ->
                            val current = swipePhotos[currentIndex]
                            if (isDelete) {
                                deleteCandidates.add(current)
                            }
                            if (currentIndex < swipePhotos.lastIndex) {
                                currentIndex += 1
                            } else {
                                // 最后一张滑动结束后，根据是否有要删除的图片决定去哪个页面
                                screenState = if (isDelete || deleteCandidates.isNotEmpty()) {
                                    ScreenState.Review
                                } else {
                                    ScreenState.AllMemories
                                }
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
                        // 删除完成后回到 Start
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
                // 本轮没有任何要删除的图片，弹出“都是美好的记忆！”的全屏提示
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
            TrashCanSketch(
                canvasSize = 160.dp,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                }
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
            TrashCanSketch(canvasSize = 120.dp)
            Spacer(modifier = Modifier.height(32.dp))
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

/**
 * 10 张卡片上下滑动：上滑 = 保留，下滑 = 删除候选
 */
@Composable
private fun SwipeScreen(
    photos: List<PhotoItem>,
    currentIndex: Int,
    deleteCount: Int,
    onDecide: (delete: Boolean) -> Unit,
    onBackToStart: () -> Unit
) {
    // 每一张图片都有自己独立的偏移量状态，切换到下一张时自动重置为 0，避免抖动
    var offsetY by remember(currentIndex) { mutableStateOf(0f) }
    var dragHeight by remember { mutableStateOf(1f) }
    // 默认静音
    var isMuted by remember { mutableStateOf(true) }
    val view = LocalView.current
    val toneGenerator = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 80) }

    // 根据拖动距离计算透明度（越远越淡，最低约 30% 透明）
    val alpha = remember(offsetY, dragHeight) {
        val maxOffset = dragHeight / 2f
        val progress = if (maxOffset > 0f) {
            (kotlin.math.abs(offsetY) / maxOffset).coerceIn(0f, 1f)
        } else {
            0f
        }
        1f - 0.7f * progress
    }

    val nextAlpha = remember(offsetY, dragHeight) {
        val maxOffset = dragHeight / 2f
        val progress = if (maxOffset > 0f) {
            (kotlin.math.abs(offsetY) / maxOffset).coerceIn(0f, 1f)
        } else {
            0f
        }
        // 下一张从 0 到最多 0.9 的透明度
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
                IconButton(onClick = { isMuted = !isMuted }) {
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
            val hasNext = currentIndex < photos.lastIndex
            val nextPhoto = if (hasNext) photos[currentIndex + 1] else null

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
                    // 1990-01-01 作为一个大致的下限，过滤掉 1970 年等无效时间
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
                        // 从文件名中提取连续的数字片段，尝试常见日期格式
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

                // 若既没有 EXIF、也没从文件名中解析出时间，但 MediaStore 有时间，则认为是导入时间
                if (displayTimeMillis != null && timeLabel != "拍摄时间") {
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
                // 先绘制下一张图片作为背景
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
                            alpha = nextAlpha
                        )
                    }
                }

                // 当前图片：上下平移 + 透明度变化
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = offsetY
                            this.alpha = alpha
                        }
                        .border(
                            width = 1.dp,
                            color = Color.LightGray,
                            shape = RoundedCornerShape(16.dp)
                        )
                        .pointerInput(photo.uri) {
                            // 上下滑判定：距离阈值，不再依赖速度，避免方向误判
                            detectDragGestures(
                                onDragStart = { _ ->
                                    val heightPx = size.height.toFloat().coerceAtLeast(1f)
                                    dragHeight = heightPx
                                },
                                onDragCancel = {
                                    offsetY = 0f
                                },
                                onDragEnd = {
                                    val heightPx = dragHeight.coerceAtLeast(1f)
                                    // 降低触发阈值：大约 1/6 屏高，更接近短视频手感
                                    val distanceThreshold = heightPx * 0.16f
                                    val currentOffset = offsetY

                                    if (kotlin.math.abs(currentOffset) < distanceThreshold) {
                                        // 距离不够，回到中心
                                        offsetY = 0f
                                        return@detectDragGestures
                                    }

                                    // 只用位移方向判定，上滑=保留，下滑=删除，不会被小反向速度干扰
                                    val isDelete = currentOffset > 0f // 下滑=删除
                                    if (isDelete && !isMuted) {
                                        // 使用 ToneGenerator 播放一声短促提示音，更可靠
                                        toneGenerator.startTone(
                                            ToneGenerator.TONE_PROP_ACK,
                                            /* durationMs = */ 150
                                        )
                                    }
                                    // 直接切换到下一张，并重置当前偏移，避免新图片抖动
                                    offsetY = 0f
                                    onDecide(isDelete)
                                }
                            ) { change, dragAmount ->
                                change.consume()
                                // 只跟随垂直位移（更接近短视频上下滑）
                                offsetY += dragAmount.y
                            }
                        }
                        .pointerInput(photo.uri) {
                            // 不滑动状态下双击，使用系统相册/图片查看器打开这张图片
                            detectTapGestures(
                                onDoubleTap = {
                                    if (kotlin.math.abs(offsetY) < dragHeight / 10f) {
                                        val intent = Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(photo.uri, "image/*")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        }
                                        runCatching { context.startActivity(intent) }
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
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(onClick = onBackToStart) {
                    Text("返回 Start")
                }
                Text(
                    text = "上滑=保留  下滑=删除",
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End
                )
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

    // 用于 Android 10+ 调用系统删除确认弹窗
    val deleteLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartIntentSenderForResult()
    ) { _ ->
        // 无论用户是确认还是取消，这一轮都结束，回到 Start
        isDeleting = false
        onDeleteFinished()
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
                                    Text(
                                        text = photo.uri.lastPathSegment ?: "图片",
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
                                // Android 11+ 优先使用“回收站”能力，让图片进入系统/相册的“最近删除”
                                val pi = MediaStore.createTrashRequest(
                                    context.contentResolver,
                                    uris,
                                    /* isTrashed = */ true
                                )
                                deleteLauncher.launch(
                                    androidx.activity.result.IntentSenderRequest.Builder(pi.intentSender)
                                        .build()
                                )
                            }

                            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                                // Android 10 只有永久删除，没有官方“回收站”API
                                val pi = MediaStore.createDeleteRequest(
                                    context.contentResolver,
                                    uris
                                )
                                deleteLauncher.launch(
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
                                    isDeleting = false
                                    onDeleteFinished()
                                }
                            }
                        }
                    },
                    enabled = !isDeleting,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (isDeleting) "删除中..." else "Delete（共 ${selected.size} 张）"
                    )
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

            // 同时读取 DATE_TAKEN / DATE_ADDED / DATE_MODIFIED，综合判断时间
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DATE_MODIFIED
            )

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

                    val uri = ContentUris.withAppendedId(collection, id)
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

@Preview(showBackground = true)
@Composable
private fun SplashPreview() {
    RandomDeleteTheme {
        SplashScreen()
    }
}