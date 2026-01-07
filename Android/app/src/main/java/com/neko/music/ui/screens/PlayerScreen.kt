package com.neko.music.ui.screens

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width as composeWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.neko.music.data.api.MusicApi
import com.neko.music.data.model.Music
import com.neko.music.ui.theme.RoseRed
import kotlinx.coroutines.launch

@Composable
fun PlayerScreen(
    music: Music,
    onBackClick: () -> Unit
) {
    var isPlaying by remember { mutableStateOf(false) }
    var isFavorite by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var currentTime by remember { mutableStateOf("0:00") }
    var totalTime by remember { mutableStateOf("0:00") }
    var lyrics by remember { mutableStateOf<List<LrcLine>>(emptyList()) }
    var showLyrics by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var musicFileUrl by remember { mutableStateOf<String?>(null) }
    var currentProgressSeconds by remember { mutableFloatStateOf(0f) }
    
    val musicApi = remember { MusicApi() }
    val scope = rememberCoroutineScope()
    
    // 加载音乐文件URL
    LaunchedEffect(music.id) {
        isLoading = true
        scope.launch {
            musicFileUrl = musicApi.getMusicFileUrl(music)
            isLoading = false
            Log.d("PlayerScreen", "音乐文件URL: $musicFileUrl")
        }
    }
    
    // 加载歌词
    LaunchedEffect(music.id) {
        scope.launch {
            val result = musicApi.getMusicLyrics(music)
            result.fold(
                onSuccess = { lyricsText ->
                    lyrics = parseLrcLyrics(lyricsText)
                    Log.d("PlayerScreen", "歌词加载成功，共 ${lyrics.size} 行")
                },
                onFailure = { error ->
                    Log.e("PlayerScreen", "歌词加载失败: ${error.message}")
                }
            )
        }
    }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .statusBarsPadding()
    ) {
        TopBar(
            onBackClick = onBackClick,
            onMenuClick = {}
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 封面视图和歌词视图容器
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            // 封面视图
            androidx.compose.animation.AnimatedVisibility(
                visible = !showLyrics,
                modifier = Modifier.fillMaxSize(),
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CoverImage(
                        music = music,
                        onClick = { showLyrics = true }
                    )
                    
                    Spacer(modifier = Modifier.height(24.dp))
                    
                    MusicInfo(
                        music = music,
                        isFavorite = isFavorite,
                        onFavoriteClick = { isFavorite = !isFavorite }
                    )
                }
            }
            
            // 歌词视图
            androidx.compose.animation.AnimatedVisibility(
                visible = showLyrics,
                modifier = Modifier.fillMaxSize(),
                enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
            ) {
                LyricsView(
                    lyrics = lyrics,
                    currentProgressSeconds = currentProgressSeconds,
                    isLoading = isLoading,
                    onClick = { showLyrics = false },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        ProgressSlider(
            progress = progress,
            currentTime = currentTime,
            totalTime = totalTime,
            isLoading = isLoading,
            onProgressChange = { 
                progress = it
                // 假设总时长为 300 秒（5分钟），计算当前秒数
                currentProgressSeconds = it * 300f
            }
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        PlaybackControls(
            isPlaying = isPlaying,
            isLoading = isLoading,
            musicFileUrl = musicFileUrl,
            onPlayPauseClick = { isPlaying = !isPlaying },
            onPreviousClick = {},
            onNextClick = {}
        )
        
        Spacer(modifier = Modifier.height(32.dp))
    }
}

@Composable
fun TopBar(
    onBackClick: () -> Unit,
    onMenuClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(
            onClick = onBackClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "返回",
                tint = Color.Black
            )
        }
        
        Text(
            text = "正在播放",
            fontSize = 16.sp,
            fontWeight = FontWeight.Medium,
            color = Color.Black
        )
        
        IconButton(
            onClick = onMenuClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "更多",
                tint = Color.Black
            )
        }
    }
}

@Composable
fun CoverImage(
    music: Music,
    onClick: () -> Unit
) {
    val musicApi = remember { MusicApi() }
    var coverUrl by remember { mutableStateOf<String?>(null) }
    
    LaunchedEffect(music.id) {
        coverUrl = musicApi.getMusicCoverUrl(music)
    }
    
    Box(
        modifier = Modifier
            .size(200.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(RoseRed.copy(alpha = 0.1f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (!coverUrl.isNullOrEmpty()) {
            AsyncImage(
                model = coverUrl,
                contentDescription = "封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        } else {
            Text(
                text = "🎵",
                fontSize = 60.sp
            )
        }
    }
}

@Composable
fun MusicInfo(
    music: Music,
    isFavorite: Boolean,
    onFavoriteClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = music.title,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold,
            color = Color.Black,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Text(
            text = music.artist,
            fontSize = 16.sp,
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        IconButton(
            onClick = onFavoriteClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                contentDescription = "收藏",
                tint = if (isFavorite) RoseRed else Color.Gray,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
fun LyricsView(
    lyrics: List<LrcLine>,
    currentProgressSeconds: Float,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = androidx.compose.foundation.lazy.LazyListState()
    val currentIndex = remember(lyrics, currentProgressSeconds) {
        lyrics.indexOfLast { it.time <= currentProgressSeconds }
    }
    
    // 自动滚动到当前歌词
    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            listState.animateScrollToItem(currentIndex, scrollOffset = 0)
        }
    }
    
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(onClick = onClick)
            .padding(horizontal = 32.dp),
        contentAlignment = Alignment.Center
    ) {
        when {
            isLoading -> {
                CircularProgressIndicator(color = RoseRed)
            }
            lyrics.isEmpty() -> {
                Text(
                    text = "暂无歌词",
                    fontSize = 16.sp,
                    color = Color.Gray
                )
            }
            else -> {
                androidx.compose.foundation.lazy.LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    items(lyrics.size) { index ->
                        val line = lyrics[index]
                        val isCurrentLine = index == currentIndex
                        
                        Text(
                            text = line.text,
                            fontSize = if (isCurrentLine) 18.sp else 14.sp,
                            fontWeight = if (isCurrentLine) FontWeight.Bold else FontWeight.Normal,
                            color = if (isCurrentLine) RoseRed else Color.Gray,
                            textAlign = TextAlign.Center,
                            lineHeight = 28.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * LRC 歌词行数据类
 */
data class LrcLine(
    val time: Float, // 时间（秒）
    val text: String // 歌词文本
)

/**
 * 解析 LRC 格式歌词
 * 解析时间标签并提取歌词文本
 */
private fun parseLrcLyrics(lrcText: String): List<LrcLine> {
    val lines = lrcText.lines()
    val result = mutableListOf<LrcLine>()
    
    for (line in lines) {
        // 匹配时间标签 [00:00.000] 或 [00:00.60]
        val timePattern = Regex("""\[(\d{2}):(\d{2})\.(\d{2,3})\]""")
        val match = timePattern.find(line)
        
        if (match != null) {
            val minutes = match.groupValues[1].toInt()
            val seconds = match.groupValues[2].toInt()
            val centiseconds = match.groupValues[3].toInt()
            
            // 计算总时间（秒）
            val time = minutes * 60 + seconds + centiseconds / 1000f
            
            // 提取歌词文本（移除所有标签）
            var text = line
            // 移除时间标签
            text = timePattern.replace(text, "")
            // 移除元数据标签 [ti:xxx], [ar:xxx] 等
            text = Regex("""\[[a-z]{2}:.*?\]""").replace(text, "")
            
            text = text.trim()
            
            if (text.isNotEmpty()) {
                result.add(LrcLine(time, text))
            }
        }
    }
    
    return result
}

@Composable
fun ProgressSlider(
    progress: Float,
    currentTime: String,
    totalTime: String,
    isLoading: Boolean,
    onProgressChange: (Float) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
    ) {
        if (!isLoading) {
            Slider(
                value = progress,
                onValueChange = onProgressChange,
                colors = SliderDefaults.colors(
                    activeTrackColor = RoseRed,
                    inactiveTrackColor = Color(0xFFE0E0E0),
                    thumbColor = RoseRed
                ),
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = currentTime,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
                Text(
                    text = totalTime,
                    fontSize = 12.sp,
                    color = Color.Gray
                )
            }
        }
    }
}

@Composable
fun PlaybackControls(
    isPlaying: Boolean,
    isLoading: Boolean,
    musicFileUrl: String?,
    onPlayPauseClick: () -> Unit,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 48.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 上一首按钮 - 使用左箭头
        IconButton(
            onClick = onPreviousClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "上一首",
                tint = Color.Black,
                modifier = Modifier.size(32.dp)
            )
        }
        
        // 播放/暂停按钮
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(RoseRed, CircleShape)
                .clickable(enabled = !isLoading && musicFileUrl != null, onClick = onPlayPauseClick),
            contentAlignment = Alignment.Center
        ) {
            when {
                isLoading -> {
                    CircularProgressIndicator(
                        color = Color.White,
                        modifier = Modifier.size(32.dp)
                    )
                }
                musicFileUrl == null -> {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = "加载中",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
                    )
                }
                else -> {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
        
        // 下一首按钮 - 使用右箭头（旋转180度）
        IconButton(
            onClick = onNextClick,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ArrowBack,
                contentDescription = "下一首",
                tint = Color.Black,
                modifier = Modifier
                    .size(32.dp)
                    .rotate(180f)
            )
        }
    }
}