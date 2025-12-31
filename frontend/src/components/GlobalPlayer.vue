<template>
  <div class="global-player">
    <div class="player-content">
      <!-- 音乐封面 -->
      <div class="cover-container" @click="goToDetails">
        <img 
          v-if="currentMusic"
          :src="getCoverUrl(currentMusic.id)" 
          :alt="currentMusic.title"
          class="music-cover"
          @error="handleImageError"
        />
        <div v-else class="music-cover placeholder-cover">🎵</div>
      </div>
      
      <!-- 音乐信息 -->
      <div class="music-info" @click.stop>
        <div v-if="currentMusic" class="music-title">{{ currentMusic.title }}</div>
        <div v-else class="music-title placeholder-text">请选择音乐播放</div>
        <div v-if="currentMusic" class="music-artist">{{ currentMusic.artist }}</div>
        <div v-else class="music-artist placeholder-text">-</div>
      </div>
      
      <!-- 播放控制 -->
      <div class="player-controls">
        <audio 
          v-if="currentMusic"
          ref="audioPlayer" 
          :src="`${API_CONFIG.BASE_URL}/api/music/file/${currentMusic.id}`" 
          @ended="onAudioEnded"
          @timeupdate="onTimeUpdate"
          @loadedmetadata="onLoadedMetadata"
        />
        
        <div class="progress-container" @click.stop>
          <span class="time">{{ formatTime(currentTime) }}</span>
          <input 
            v-if="currentMusic"
            type="range" 
            class="progress-bar" 
            :value="progress" 
            @input="onProgressChange"
            :max="duration"
          />
          <span class="time">{{ formatTime(duration) }}</span>
        </div>
        
        <div class="control-buttons" @click.stop>
          <!-- 上一曲按钮 -->
          <button @click="playPrevious" class="prev-btn" title="上一曲" :disabled="!currentMusic">
            <svg class="btn-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M6 6h2v12H6zm3.5 6l8.5 6V6z"/>
            </svg>
          </button>
          
          <!-- 播放模式按钮 -->
          <button @click="togglePlaybackMode" class="mode-btn" :title="getPlaybackModeTitle()">
            <svg
                v-if="playbackMode === 'list_repeat'"
                class="btn-icon"
                viewBox="0 0 1024 1024"
                xmlns="http://www.w3.org/2000/svg">
              <path
                  d="M361.5 727.8c-119.1 0-215.9-96.9-215.9-215.9 0-119.1 96.9-215.9 215.9-215.9 2.3 0 4.6-0.2 6.8-0.6v58.3c0 12.3 14 19.4 23.9 12.1l132.6-97.6c8.1-6 8.1-18.2 0-24.2l-132.6-97.6c-9.9-7.3-23.9-0.2-23.9 12.1v58.1c-2.2-0.4-4.5-0.6-6.8-0.6-39.8 0-78.5 7.9-115 23.4-35.2 15-66.8 36.3-94 63.5s-48.6 58.8-63.5 94c-15.5 36.5-23.4 75.2-23.4 115s7.9 78.5 23.4 115c15 35.2 36.3 66.8 63.5 94s58.8 48.6 94 63.5c36.5 15.5 75.2 23.4 115 23.4 22.1 0 40-17.9 40-40s-17.9-40-40-40zM938.2 396.9c-15-35.2-36.3-66.8-63.5-94s-58.8-48.6-94-63.5c-36.5-15.5-75.2-23.4-115-23.4-22.1 0-40 17.9-40 40s17.9 40 40 40c119.1 0 215.9 96.9 215.9 215.9 0 119.1-96.9 215.9-215.9 215.9-4.1 0-8.1 0.6-11.8 1.8v-60.8c0-12.3-14-19.4-23.9-12.1l-132.6 97.6c-8.1 6-8.1 18.2 0 24.2L629.9 876c9.9 7.3 23.9 0.2 23.9-12.1V806c3.7 1.2 7.7 1.8 11.8 1.8 39.8 0 78.5-7.9 115-23.4 35.2-15 66.8-36.3 94-63.5s48.6-58.8 63.5-94c15.5-36.5 23.4-75.2 23.4-115s-7.8-78.5-23.3-115z"
                  fill="#ffffff"
              />
            </svg>

            <svg v-else-if="playbackMode === 'single_repeat'" class="btn-icon" viewBox="0 0 1024 1024" version="1.1" xmlns="http://www.w3.org/2000/svg" p-id="20390" width="128" height="128">
              <path d="M507.008 122.752a42.666667 42.666667 0 0 0-30.165333 72.832l17.749333 17.749333H383.317333A298.666667 298.666667 0 0 0 232.533333 769.834667a42.666667 42.666667 0 1 0 44.672-72.149334q-23.808-13.909333-44.714666-34.816Q169.984 600.32 169.984 512q0-88.362667 62.506667-150.869333Q294.954667 298.666667 383.317333 298.666667H597.333333a42.666667 42.666667 0 0 0 30.336-12.586667 42.666667 42.666667 0 0 0 0-60.330667l-12.373333-12.373333h25.301333L639.317333 213.333333h-24.064l-78.08-78.08a42.666667 42.666667 0 0 0-30.165333-12.501333zM937.984 512c0-110.506667-59.946667-206.933333-149.12-258.56a42.666667 42.666667 0 1 0-39.424 75.264q21.589333 13.269333 40.746667 32.426667Q852.650667 423.68 852.650667 512q0 88.362667-62.464 150.869333Q727.68 725.333333 639.317333 725.333333h-209.066666a42.666667 42.666667 0 0 0-33.621334 12.373334l-0.512 0.512a42.666667 42.666667 0 0 0 3.370667 62.677333l87.637333 87.637333a42.666667 42.666667 0 0 0 60.373334-60.330666l-17.536-17.493334h109.354666a298.666667 298.666667 0 0 0 298.666667-298.709333z" p-id="20391"></path>
              <path d="M469.333333 597.333333v-170.666666a42.666667 42.666667 0 1 1 85.333334 0v170.666666a42.666667 42.666667 0 0 1-85.333334 0z" p-id="20392"></path>
            </svg>
            <svg v-else class="btn-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M10.59 9.17L5.41 4 4 5.41l5.17 5.17 1.42-1.41zM14.5 4l2.04 2.04L4 18.59 5.41 20 17.96 7.46 20 9.5V4h-5.5zm.33 9.41l-1.41 1.41 3.13 3.13L14.5 20H20v-5.5l-2.04 2.04-3.13-3.13z"/>
            </svg>
          </button>
          
          <!-- 播放/暂停按钮 -->
          <button @click="togglePlayPause" class="play-pause-btn" :disabled="!currentMusic">
            <svg v-if="isPlaying && currentMusic" class="btn-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M6 19h4V5H6v14zm8-14v14h4V5h-4z"/>
            </svg>
            <svg v-else class="btn-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M8 5v14l11-7z"/>
            </svg>
          </button>
          
          <!-- 下一曲按钮 -->
          <button @click="playNext" class="next-btn" title="下一曲" :disabled="!currentMusic">
            <svg class="btn-icon" viewBox="0 0 24 24" fill="currentColor">
              <path d="M6 18l8.5-6L6 6v12zM16 6v12h2V6h-2z"/>
            </svg>
          </button>
          
          <!-- 播放列表按钮 -->
          <button @click="togglePlaylist" class="playlist-btn" title="播放列表">
            <svg class="btn-icon" viewBox="0 0 1024 1024" xmlns="http://www.w3.org/2000/svg">
              <path d="M981.333333 533.333333a21.333333 21.333333 0 0 1-21.333333 21.333334H448a21.333333 21.333333 0 0 1 0-42.666667h512a21.333333 21.333333 0 0 1 21.333333 21.333333zM533.333333 170.666667h426.666667a21.333333 21.333333 0 0 0 0-42.666667H533.333333a21.333333 21.333333 0 0 0 0 42.666667z m426.666667 725.333333H64a21.333333 21.333333 0 0 0 0 42.666667h896a21.333333 21.333333 0 0 0 0-42.666667zM89.66 696.753333C117.333333 715.186667 153.646667 725.333333 192 725.333333s74.7-10.146667 102.34-28.58c14.253333-9.5 25.56-20.746667 33.613333-33.44C336.833333 649.333333 341.333333 634.3 341.333333 618.666667V182a140.893333 140.893333 0 0 0 30.966667 27.82A21.18 21.18 0 0 0 376.666667 212c8.713333 3.2 16.773333 8.606667 23.953333 16.086667 16.733333 17.42 23.806667 41.146667 26.533333 53.733333a21.333333 21.333333 0 0 0 41.706667-9.026667c-4.5-20.773333-14.666667-50.513333-37.466667-74.266666-11.04-11.493333-23.64-20.093333-37.493333-25.606667-10.306667-7.126667-19.44-16.58-27.153333-28.133333-19.64-29.393333-24.373333-64.04-25.446667-82.08A21.333333 21.333333 0 0 0 298.666667 64v479.586667c-1.413333-1.02-2.846667-2-4.326667-3.006667C266.7 522.146667 230.353333 512 192 512s-74.666667 10.146667-102.34 28.58C75.406667 550.08 64.1 561.333333 56.046667 574 47.166667 588 42.666667 603.033333 42.666667 618.666667s4.5 30.666667 13.38 44.666666c8.053333 12.666667 19.36 23.92 33.613333 33.42z" fill="currentColor"/>
            </svg>
          </button>
        </div>
      </div>
      
      <!-- 播放列表弹窗 -->
      <div v-if="showPlaylist" class="playlist-container">
        <div class="playlist-header">
          <h3>播放列表</h3>
          <button @click="togglePlaylist" class="close-playlist">✕</button>
        </div>
        <div class="playlist-items">
          <div 
            v-for="(item, index) in playlist" 
            :key="item.id" 
            class="playlist-item" 
            :class="{ 'current': currentMusic && item.id === currentMusic.id }"
            @click="playFromPlaylist(index)"
          >
            <div class="playlist-item-info">
              <span class="playlist-item-title">{{ item.title }}</span>
              <span class="playlist-item-artist">{{ item.artist }}</span>
            </div>
            <span v-if="currentMusic && item.id === currentMusic.id" class="current-indicator">▶</span>
          </div>
        </div>
      </div>
      
      <!-- 歌词显示区域 -->
      <div class="lyrics-container" @click.stop>
        <div class="lyrics-content">
          <div class="lyric-line" :class="{ 'active': isCurrentLyric(0), 'active-enter': isCurrentLyric(0) && currentAnimationIndex === 0 }">
            {{ getLyricLine(0) }}
          </div>
          <div class="lyric-line" :class="{ 'active': isCurrentLyric(1), 'active-enter': isCurrentLyric(1) && currentAnimationIndex === 1 }">
            {{ getLyricLine(1) }}
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { useRouter } from 'vue-router'
import API_CONFIG from '@/config/apiConfig.js'

const router = useRouter()

// 从localStorage获取当前播放的音乐信息
const currentMusic = ref(JSON.parse(localStorage.getItem('currentPlayingMusic')) || null)
const audioPlayer = ref(null)
const isPlaying = ref(false)
const currentTime = ref(0)
const duration = ref(0)
const progress = ref(0)
const lyrics = ref('')
const parsedLyrics = ref([])
const lyricsContent = ref(null)
const currentAnimationIndex = ref(-1)

// 播放模式相关状态
const playbackMode = ref('list_repeat') // 'list_repeat', 'single_repeat', 'shuffle'
const playlist = ref([])

// 记录上一个歌词索引
let previousLyricIndex = -1

// 播放/暂停控制
const togglePlayPause = () => {
  if (audioPlayer.value && currentMusic.value) {
    if (isPlaying.value) {
      // 暂停：直接暂停，避免重音
      isPlaying.value = false
      updateGlobalPlayerState()
      // 广播播放状态变化
      broadcastPlayerStateChange()
      // 立即暂停音频并静音
      if (audioPlayer.value) {
        audioPlayer.value.volume = 0;
        audioPlayer.value.pause();
      }
      // 更新媒体会话播放状态
      updateMediaSessionPlaybackState()
    } else {
      // 播放：立即更新状态，然后淡入播放
      isPlaying.value = true
      updateGlobalPlayerState()
      // 广播播放状态变化
      broadcastPlayerStateChange()
      fadeIn(audioPlayer.value)
      // 更新媒体会话播放状态
      updateMediaSessionPlaybackState()
    }
  }
}

// 音量淡出效果
const fadeOut = (audioElement) => {
  if (!audioElement) return

  // 为了防止暂停时出现重音，先快速将音量降低到0，然后暂停
  // 使用更快速的淡出效果
  const originalVolume = audioElement.volume;
  
  // 立即设置音量为0以避免重音，然后暂停音频
  audioElement.volume = 0;
  audioElement.pause();
}

// 音量淡入效果
const fadeIn = (audioElement) => {
  if (!audioElement) return
  
  // 如果音频暂停了，需要先播放
  if (audioElement.paused) {
    audioElement.volume = 0
    audioElement.play().catch(e => console.log('播放被阻止:', e))
  }
  
  const targetVolume = 1
  const steps = 30 // 与之前保持一致
  const increment = targetVolume / steps
  let currentStep = 0
  
  const fade = () => {
    if (currentStep < steps && audioElement.volume < targetVolume) {
      audioElement.volume = Math.min(targetVolume, audioElement.volume + increment)
      currentStep++
      requestAnimationFrame(fade)
    } else {
      audioElement.volume = targetVolume
    }
  }
  
  fade()
}

// 音频结束事件
// 音频结束事件 - 现在根据播放模式处理
const onAudioEnded = () => {
  if (playbackMode.value === 'single_repeat') {
    // 单曲循环：重新播放当前歌曲
    if (audioPlayer.value && currentMusic.value) {
      audioPlayer.value.currentTime = 0
      currentTime.value = 0
      fadeIn(audioPlayer.value)
      // 更新媒体会话播放状态
      updateMediaSessionPlaybackState()
    }
  } else if (playbackMode.value === 'shuffle' && playlist.value.length > 1) {
    // 随机播放：播放列表中的随机歌曲
    playNextInShuffle()
  } else {
    // 列表循环：播放下一首
    playNext()
  }
  
  updateGlobalPlayerState()
  // 广播播放状态变化
  broadcastPlayerStateChange()
  // 确保音量为0
  if (audioPlayer.value) {
    audioPlayer.value.volume = 0
  }
}

// 时间更新事件
const onTimeUpdate = () => {
  if (audioPlayer.value) {
    currentTime.value = audioPlayer.value.currentTime
    progress.value = currentTime.value
    updateGlobalPlayerState()
    
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
    
    // 检测当前歌词是否发生变化，如果是，则触发动画
    if (parsedLyrics.value.length > 0) {
      let currentLyricIndex = -1
      for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
        const lyric = parsedLyrics.value[i]
        if (currentTime.value >= lyric.time) {
          currentLyricIndex = i
          break
        }
      }
      
      // 如果当前歌词索引发生变化，则触发动画
      if (previousLyricIndex !== currentLyricIndex && currentLyricIndex !== -1) {
        currentAnimationIndex.value = 0 // 为当前歌词行触发动画
        previousLyricIndex = currentLyricIndex
        // 动画结束后清除动画索引
        setTimeout(() => {
          currentAnimationIndex.value = -1
        }, 600)
      }
    }
  }
}

// 音频元数据加载完成
const onLoadedMetadata = () => {
  if (audioPlayer.value) {
    duration.value = audioPlayer.value.duration
    updateGlobalPlayerState()
    // 广播播放状态变化
    broadcastPlayerStateChange()
    
    // 重置歌词索引
    previousLyricIndex = -1
    
    // 加载歌词
    if (currentMusic.value) {
      loadLyrics(currentMusic.value.id)
    }
    
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
  }
}

// 进度条变化
const onProgressChange = (event) => {
  const newTime = parseFloat(event.target.value)
  if (audioPlayer.value) {
    audioPlayer.value.currentTime = newTime
    currentTime.value = newTime
    updateGlobalPlayerState()
    // 广播播放状态变化
    broadcastPlayerStateChange()
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
  }
}

// 更新全局播放器状态
const updateGlobalPlayerState = () => {
  const state = {
    isPlaying: isPlaying.value,
    currentTime: currentTime.value,
    duration: duration.value,
    playbackMode: playbackMode.value
  };
  localStorage.setItem('globalPlayerState', JSON.stringify(state));
}

// 广播播放器状态变化
const broadcastPlayerStateChange = () => {
  // 创建自定义事件来通知播放状态变化
  const event = new CustomEvent('playerStateChange', {
    detail: {
      isPlaying: isPlaying.value,
      currentTime: currentTime.value,
      duration: duration.value,
      currentMusic: currentMusic.value
    }
  });
  window.dispatchEvent(event);
}

// 格式化时间（秒转分:秒）
const formatTime = (seconds) => {
  if (isNaN(seconds) || seconds < 0) return '0:00'
  
  const min = Math.floor(seconds / 60)
  const sec = Math.floor(seconds % 60)
  return `${min}:${sec < 10 ? '0' : ''}${sec}`
}

// 跳转到音乐详情页面
const goToDetails = () => {
  if (currentMusic.value) {
    // 跳转到音乐详情页面
    router.push(`/detail/${currentMusic.value.id}`)
  }
}

// 获取音乐封面URL
const getCoverUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/cover/${musicId}`
}

// 获取歌词
const getLyricsUrl = (musicId) => {
  return `${API_CONFIG.BASE_URL}/api/music/lyrics/${musicId}`
}

// 加载歌词
const loadLyrics = async (musicId) => {
  try {
    const response = await fetch(getLyricsUrl(musicId))
    if (response.ok) {
      const data = await response.json()
      if (data.success) {
        lyrics.value = data.data
        parseLrcLyrics(data.data)
      } else {
        lyrics.value = ''
        parsedLyrics.value = []
      }
    } else {
      lyrics.value = ''
      parsedLyrics.value = []
    }
  } catch (error) {
    console.error('加载歌词失败:', error)
    lyrics.value = ''
    parsedLyrics.value = []
  }
}

// 解析LRC歌词格式
const parseLrcLyrics = (lrcText) => {
  if (!lrcText) {
    parsedLyrics.value = []
    return
  }
  
  const lines = lrcText.split('\n')
  const parsed = []
  
  for (const line of lines) {
    // 匹配 [mm:ss.xx] 或 [mm:ss.xxx] 格式的时间标签
    const timeRegex = /\[(\d{2}):(\d{2})\.(\d{2,3})\]/g
    let match
    const matches = []
    
    // 收集所有时间标签
    while ((match = timeRegex.exec(line)) !== null) {
      matches.push({
        minutes: parseInt(match[1]),
        seconds: parseInt(match[2]),
        milliseconds: parseInt(match[3]),
        index: match.index
      })
    }
    
    // 提取歌词文本（去除时间标签）
    const text = line.replace(/\[(\d{2}):(\d{2})\.(\d{2,3})\]/g, '').trim()
    
    // 为每个时间标签创建一个歌词项
    for (const timeMatch of matches) {
      // 根据毫秒部分的位数正确计算秒数
      let millisecondsDivisor
      if (timeMatch.milliseconds.toString().length === 2) {
        millisecondsDivisor = 100 // 两位毫秒，如 .25
      } else {
        millisecondsDivisor = 1000 // 三位毫秒，如 .250
      }
      const timeInSeconds = timeMatch.minutes * 60 + timeMatch.seconds + (timeMatch.milliseconds / millisecondsDivisor)
      parsed.push({
        time: timeInSeconds,
        text: text
      })
    }
  }
  
  // 按时间排序
  parsed.sort((a, b) => a.time - b.time)
  parsedLyrics.value = parsed
}

// 获取指定索引的歌词行文本
const getLyricLine = (offset) => {
  if (!currentMusic.value || parsedLyrics.value.length === 0) {
    return currentMusic.value ? (offset === 0 ? '暂无歌词' : '') : '请选择音乐播放'
  }
  
  // 查找当前时间点对应的歌词索引
  let currentLyricIndex = -1
  for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
    const lyric = parsedLyrics.value[i]
    if (currentTime.value >= lyric.time) {
      currentLyricIndex = i
      break
    }
  }
  
  // 根据偏移量返回对应的歌词
  const targetIndex = currentLyricIndex + offset
  if (targetIndex >= 0 && targetIndex < parsedLyrics.value.length) {
    return parsedLyrics.value[targetIndex].text || ''
  }
  
  // 如果超出范围但不是第一行，返回空字符串
  if (offset > 0) {
    return ''
  }
  
  // 如果还没到第一句歌词的时间，显示提示信息
  if (currentLyricIndex === -1 && parsedLyrics.value.length > 0) {
    return '即将开始...'
  }
  
  return '...'
}

// 判断指定偏移量的歌词行是否是当前歌词
const isCurrentLyric = (offset) => {
  if (!currentMusic.value || parsedLyrics.value.length === 0) {
    return offset === 0 && !currentMusic.value // 只有在没有选择音乐时，第一行才"活跃"
  }
  
  // 查找当前时间点对应的歌词索引
  let currentLyricIndex = -1
  for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
    const lyric = parsedLyrics.value[i]
    if (currentTime.value >= lyric.time) {
      currentLyricIndex = i
      break
    }
  }
  
  // 检查指定偏移量的行是否是当前行
  return (currentLyricIndex + offset) >= 0 && (currentLyricIndex + offset) < parsedLyrics.value.length
}

// 获取当前应该显示的歌词文本 (保留此函数以备后续可能需要)
const getCurrentLyricText = () => {
  if (parsedLyrics.value.length === 0) {
    return '暂无歌词'
  }
  
  // 查找当前时间点对应的歌词
  for (let i = parsedLyrics.value.length - 1; i >= 0; i--) {
    const lyric = parsedLyrics.value[i]
    if (currentTime.value >= lyric.time) {
      return lyric.text || '...'
    }
  }
  
  // 如果还没到第一句歌词的时间，显示提示信息
  return '即将开始...'
}

// 处理封面图片加载错误
const handleImageError = (event) => {
  event.target.src = `${API_CONFIG.BASE_URL}/api/music/cover/`
}

// 播放模式切换函数
const togglePlaybackMode = () => {
  if (playbackMode.value === 'list_repeat') {
    playbackMode.value = 'single_repeat'
  } else if (playbackMode.value === 'single_repeat') {
    playbackMode.value = 'shuffle'
  } else {
    playbackMode.value = 'list_repeat'
  }
  // 保存播放模式到localStorage
  localStorage.setItem('playbackMode', playbackMode.value)
}

// 获取播放模式标题
const getPlaybackModeTitle = () => {
  if (playbackMode.value === 'list_repeat') {
    return '列表循环'
  } else if (playbackMode.value === 'single_repeat') {
    return '单曲循环'
  } else {
    return '随机播放'
  }
}

// 播放列表显示状态
const showPlaylist = ref(false)

// 切换播放列表显示
const togglePlaylist = () => {
  showPlaylist.value = !showPlaylist.value
}

// 从播放列表播放
const playFromPlaylist = (index) => {
  if (playlist.value[index]) {
    // 先暂停当前音频
    if (audioPlayer.value && !audioPlayer.value.paused) {
      audioPlayer.value.pause();
    }
    
    // 重置播放时间并立即更新UI
    currentTime.value = 0
    duration.value = 0
    progress.value = 0
    updateGlobalPlayerState()
    
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
    
    // 设置为当前播放的音乐
    localStorage.setItem('currentPlayingMusic', JSON.stringify(playlist.value[index]))
    currentMusic.value = playlist.value[index]
    isPlaying.value = true
    
    // 重新加载歌词
    loadLyrics(playlist.value[index].id)
    
    // 确保音频元素重新加载资源
    if (audioPlayer.value) {
      // 确保音频元素在加载新资源前已重置
      audioPlayer.value.currentTime = 0
      audioPlayer.value.load()
      fadeIn(audioPlayer.value)
    }
    
    // 更新媒体会话元数据
    updateMediaSessionMetadata(playlist.value[index])
    // 更新播放状态
    updateMediaSessionPlaybackState()
  }
  // 关闭播放列表
  showPlaylist.value = false
}

// 从localStorage加载播放模式
const loadPlaybackMode = () => {
  const savedMode = localStorage.getItem('playbackMode')
  if (savedMode && ['list_repeat', 'single_repeat', 'shuffle'].includes(savedMode)) {
    playbackMode.value = savedMode
  } else {
    // 默认设置为列表循环
    playbackMode.value = 'list_repeat'
    localStorage.setItem('playbackMode', 'list_repeat')
  }
}

// 播放下一首
const playNext = () => {
  if (!currentMusic.value || playlist.value.length === 0) return
  
  const currentIndex = playlist.value.findIndex(item => item.id === currentMusic.value.id)
  let nextIndex
  
  if (playbackMode.value === 'shuffle') {
    nextIndex = getRandomIndex(currentIndex)
  } else {
    nextIndex = (currentIndex + 1) % playlist.value.length
  }
  
  if (nextIndex !== -1 && playlist.value[nextIndex]) {
    // 先暂停当前音频
    if (audioPlayer.value && !audioPlayer.value.paused) {
      audioPlayer.value.pause();
    }
    
    // 重置播放时间并立即更新UI
    currentTime.value = 0
    duration.value = 0
    progress.value = 0
    updateGlobalPlayerState()
    
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
    
    // 设置新音乐到localStorage
    localStorage.setItem('currentPlayingMusic', JSON.stringify(playlist.value[nextIndex]))
    currentMusic.value = playlist.value[nextIndex]
    
    // 加载新音乐的歌词
    loadLyrics(playlist.value[nextIndex].id)
    
    // 加载新音频资源
    if (audioPlayer.value) {
      // 确保音频元素在加载新资源前已重置
      audioPlayer.value.currentTime = 0
      audioPlayer.value.load()
      fadeIn(audioPlayer.value)
    }
    
    // 更新媒体会话元数据
    updateMediaSessionMetadata(playlist.value[nextIndex])
    // 更新播放状态
    updateMediaSessionPlaybackState()
  }
}

// 播放上一首
const playPrevious = () => {
  if (!currentMusic.value || playlist.value.length === 0) return
  
  const currentIndex = playlist.value.findIndex(item => item.id === currentMusic.value.id)
  let prevIndex
  
  if (playbackMode.value === 'shuffle') {
    prevIndex = getRandomIndex(currentIndex)
  } else {
    prevIndex = (currentIndex - 1 + playlist.value.length) % playlist.value.length
  }
  
  if (prevIndex !== -1 && playlist.value[prevIndex]) {
    // 先暂停当前音频
    if (audioPlayer.value && !audioPlayer.value.paused) {
      audioPlayer.value.pause();
    }
    
    // 重置播放时间并立即更新UI
    currentTime.value = 0
    duration.value = 0
    progress.value = 0
    updateGlobalPlayerState()
    
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
    
    // 设置新音乐到localStorage
    localStorage.setItem('currentPlayingMusic', JSON.stringify(playlist.value[prevIndex]))
    currentMusic.value = playlist.value[prevIndex]
    
    // 加载新音乐的歌词
    loadLyrics(playlist.value[prevIndex].id)
    
    // 加载新音频资源
    if (audioPlayer.value) {
      // 确保音频元素在加载新资源前已重置
      audioPlayer.value.currentTime = 0
      audioPlayer.value.load()
      fadeIn(audioPlayer.value)
    }
    
    // 更新媒体会话元数据
    updateMediaSessionMetadata(playlist.value[prevIndex])
    // 更新播放状态
    updateMediaSessionPlaybackState()
  }
}

// 获取随机索引（排除当前索引）
const getRandomIndex = (currentIndex) => {
  if (playlist.value.length <= 1) return currentIndex
  
  let randomIndex
  do {
    randomIndex = Math.floor(Math.random() * playlist.value.length)
  } while (randomIndex === currentIndex && playlist.value.length > 1)
  
  return randomIndex
}

// 播放下一首（随机模式）
const playNextInShuffle = () => {
  if (!currentMusic.value || playlist.value.length === 0) return
  
  const currentIndex = playlist.value.findIndex(item => item.id === currentMusic.value.id)
  const nextIndex = getRandomIndex(currentIndex)
  
  if (nextIndex !== -1 && playlist.value[nextIndex]) {
    // 先暂停当前音频
    if (audioPlayer.value && !audioPlayer.value.paused) {
      audioPlayer.value.pause();
    }
    
    // 重置播放时间并立即更新UI
    currentTime.value = 0
    duration.value = 0
    progress.value = 0
    updateGlobalPlayerState()
    
    // 更新媒体会话播放位置
    updateMediaSessionPositionState()
    
    // 设置新音乐到localStorage
    localStorage.setItem('currentPlayingMusic', JSON.stringify(playlist.value[nextIndex]))
    currentMusic.value = playlist.value[nextIndex]
    
    // 加载新音乐的歌词
    loadLyrics(playlist.value[nextIndex].id)
    
    // 加载新音频资源
    if (audioPlayer.value) {
      // 确保音频元素在加载新资源前已重置
      audioPlayer.value.currentTime = 0
      audioPlayer.value.load()
      fadeIn(audioPlayer.value)
    }
    
    // 更新媒体会话元数据
    updateMediaSessionMetadata(playlist.value[nextIndex])
    // 更新播放状态
    updateMediaSessionPlaybackState()
  }
}

// 监听音频播放状态
watch(audioPlayer, (newPlayer) => {
  if (newPlayer) {
    newPlayer.addEventListener('play', () => {
      isPlaying.value = true
      updateGlobalPlayerState();
      // 广播播放状态变化
      broadcastPlayerStateChange();
      // 更新媒体会话播放状态
      updateMediaSessionPlaybackState();
    })
    
    newPlayer.addEventListener('pause', () => {
      isPlaying.value = false
      updateGlobalPlayerState();
      // 广播播放状态变化
      broadcastPlayerStateChange();
      // 更新媒体会话播放状态
      updateMediaSessionPlaybackState();
    })
  }
}, { immediate: true })

// 监听localStorage变化，响应播放音乐的改变
const handleStorageChange = (e) => {
  if (e.key === 'currentPlayingMusic') {
    // 只有在音乐实际改变时才重置播放器
    const newMusic = e.newValue ? JSON.parse(e.newValue) : null;
    if (newMusic && (!currentMusic.value || newMusic.id !== currentMusic.value.id)) {
      // 先暂停当前音频
      if (audioPlayer.value && !audioPlayer.value.paused) {
        audioPlayer.value.pause();
      }
      
      // 音乐改变了，更新当前音乐并重置播放器
      currentMusic.value = newMusic;
      
      // 重置播放时间并立即更新UI
      currentTime.value = 0
      duration.value = 0
      progress.value = 0
      updateGlobalPlayerState()
      
      // 更新媒体会话播放位置
      updateMediaSessionPositionState()
      
      if (audioPlayer.value) {
        // 重置音频元素的播放时间
        audioPlayer.value.currentTime = 0
        audioPlayer.value.load();
        // 重置播放状态
        isPlaying.value = false;
      }
      
      // 加载新音乐的歌词
      if (newMusic) {
        loadLyrics(newMusic.id)
        // 更新媒体会话元数据
        updateMediaSessionMetadata(newMusic)
        // 更新媒体会话播放位置
        updateMediaSessionPositionState()
      } else {
        lyrics.value = ''
        parsedLyrics.value = []
        // 清除媒体会话元数据
        if ('mediaSession' in navigator) {
          navigator.mediaSession.metadata = null
        }
        // 更新媒体会话播放位置
        updateMediaSessionPositionState()
      }
    } else if (!e.newValue) {
      // 没有音乐了，暂停播放器
      currentMusic.value = null;
      if (audioPlayer.value) {
        audioPlayer.value.pause();
        // 重置播放时间
        currentTime.value = 0;
        progress.value = 0;
        isPlaying.value = false;
        duration.value = 0;
        updateGlobalPlayerState();
        // 广播播放状态变化
        broadcastPlayerStateChange();
        // 更新媒体会话播放状态
        updateMediaSessionPlaybackState();
        // 更新媒体会话播放位置
        updateMediaSessionPositionState()
      }
      // 清空歌词
      lyrics.value = ''
      parsedLyrics.value = []
      // 清除媒体会话元数据
      if ('mediaSession' in navigator) {
        navigator.mediaSession.metadata = null
      }
    }
  } else if (e.key === 'globalPlayerState') {
    // 从播放页面接收状态更新
    if (e.newValue) {
      const state = JSON.parse(e.newValue);
      // 更新播放器状态，无论audio元素是否准备好
      isPlaying.value = state.isPlaying;
      currentTime.value = state.currentTime;
      duration.value = state.duration;
      progress.value = state.currentTime;
      
      // 如果有audio元素则同步操作
      if (audioPlayer.value && currentMusic.value) {
        // 等待音频加载完成再执行操作
        const playWhenReady = () => {
          if (audioPlayer.value) {
            audioPlayer.value.currentTime = state.currentTime;
            if (state.isPlaying) {
              audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
            } else {
              audioPlayer.value.pause();
            }
          }
        };
        
        if (audioPlayer.value.readyState >= 2) { // HAVE_CURRENT_DATA
          playWhenReady();
        } else {
          audioPlayer.value.addEventListener('loadeddata', playWhenReady, { once: true });
        }
        updateGlobalPlayerState();
        // 更新媒体会话播放状态
        updateMediaSessionPlaybackState();
      } else if (currentMusic.value) {
        // 如果audio元素还没准备好，等待并执行操作
        const handleMetadata = () => {
          if (audioPlayer.value) {
            audioPlayer.value.currentTime = state.currentTime;
            if (state.isPlaying) {
              audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
            } else {
              audioPlayer.value.pause();
            }
          }
        };
        
        audioPlayer.value?.addEventListener('loadedmetadata', handleMetadata, { once: true });
      }
      // 更新媒体会话播放位置
      updateMediaSessionPositionState()
    }
  }
}

// 处理自定义播放状态变化事件
const handlePlayerStateChange = (e) => {
  const state = e.detail;
  
  // 检查是否正在切换到新音乐
  if (currentMusic.value && state.currentMusic && currentMusic.value.id !== state.currentMusic.id) {
    // 如果是切换到新音乐，重置播放时间
    currentTime.value = 0;
    duration.value = 0;
    progress.value = 0;
    updateGlobalPlayerState();
    updateMediaSessionPositionState();
  } else {
    // 否则是同一首音乐的时间更新
    currentTime.value = state.currentTime;
    duration.value = state.duration;
    progress.value = state.currentTime;
  }

  // 如果有audio元素则同步操作
  if (audioPlayer.value && currentMusic.value && currentMusic.value.id === state.currentMusic?.id) {
    // 等待音频加载完成再执行操作
    const performStateChange = () => {
      if (audioPlayer.value) {
        audioPlayer.value.currentTime = state.currentTime;
        if (state.isPlaying) {
          // 如果是播放状态，执行淡入
          isPlaying.value = true;
          updateGlobalPlayerState();
          fadeIn(audioPlayer.value);
          // 更新媒体会话播放状态
          updateMediaSessionPlaybackState();
        } else {
          // 如果是暂停状态，立即暂停并静音，避免重音
          isPlaying.value = false;
          if (audioPlayer.value) {
            audioPlayer.value.volume = 0;
            audioPlayer.value.pause();
          }
          updateGlobalPlayerState();
          // 更新媒体会话播放状态
          updateMediaSessionPlaybackState();
        }
      }
      // 更新媒体会话播放位置
      updateMediaSessionPositionState();
    };

    if (audioPlayer.value.readyState >= 2) { // HAVE_CURRENT_DATA
      performStateChange();
    } else {
      audioPlayer.value.addEventListener('loadeddata', performStateChange, { once: true });
    }
  } else if (currentMusic.value && currentMusic.value.id === state.currentMusic?.id) {
    // 如果audio元素还没准备好，等待并执行操作
    const handleMetadata = () => {
      if (audioPlayer.value) {
        audioPlayer.value.currentTime = state.currentTime;
        if (state.isPlaying) {
          // 如果是播放状态，执行淡入
          isPlaying.value = true;
          updateGlobalPlayerState();
          fadeIn(audioPlayer.value);
          // 更新媒体会话播放状态
          updateMediaSessionPlaybackState();
        } else {
          // 如果是暂停状态，立即暂停并静音，避免重音
          isPlaying.value = false;
          if (audioPlayer.value) {
            audioPlayer.value.volume = 0;
            audioPlayer.value.pause();
          }
          updateGlobalPlayerState();
          // 更新媒体会话播放状态
          updateMediaSessionPlaybackState();
        }
      }
      // 更新媒体会话播放位置
      updateMediaSessionPositionState();
    };

    audioPlayer.value?.addEventListener('loadedmetadata', handleMetadata, { once: true });
  }
}

onMounted(() => {
  // 监听storage事件，以响应其他标签页的播放变化
  window.addEventListener('storage', handleStorageChange)
  // 监听自定义事件，以响应播放页面的状态变化
  window.addEventListener('playerStateChange', handlePlayerStateChange)
  
  // 初始化当前播放音乐
  const storedMusic = localStorage.getItem('currentPlayingMusic')
  if (storedMusic) {
    currentMusic.value = JSON.parse(storedMusic)
    
    // 初始化时加载歌词
    if (currentMusic.value) {
      loadLyrics(currentMusic.value.id)
      // 初始化媒体会话
      initializeMediaSession(currentMusic.value)
    }
  }
  
  // 初始化时从localStorage获取播放状态
  const storedState = localStorage.getItem('globalPlayerState');
  if (storedState) {
    const state = JSON.parse(storedState);
    currentTime.value = state.currentTime;
    duration.value = state.duration;
    progress.value = state.currentTime;
    
    // 如果全局播放器应该正在播放，则同步播放状态
    if (state.isPlaying && audioPlayer.value && currentMusic.value) {
      audioPlayer.value.play().catch(e => console.log('播放被阻止:', e));
      isPlaying.value = true;
    }
  }
  
  // 加载播放模式
  loadPlaybackMode()
  
  // 加载播放列表
  loadPlaylist()
  
  // 初始化媒体会话API
  initializeMediaSession()
})

// 加载播放列表
const loadPlaylist = async () => {
  try {
    // 获取所有音乐（通过搜索空字符串）
    const response = await fetch(`${API_CONFIG.BASE_URL}/api/music/search`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json'
      },
      body: JSON.stringify({ query: '' })
    })
    if (response.ok) {
      const data = await response.json()
      if (data.success) {
        playlist.value = data.results || []
      }
    }
  } catch (error) {
    console.error('加载播放列表失败:', error)
  }
}

// 初始化媒体会话API
const initializeMediaSession = (music = null) => {
  if ('mediaSession' in navigator) {
    try {
      // 设置媒体操作处理程序
      navigator.mediaSession.setActionHandler('play', () => {
        if (audioPlayer.value && currentMusic.value) {
          isPlaying.value = true
          updateGlobalPlayerState()
          fadeIn(audioPlayer.value)
        }
      })
      
      navigator.mediaSession.setActionHandler('pause', () => {
        if (audioPlayer.value && currentMusic.value) {
          isPlaying.value = false
          updateGlobalPlayerState()
          // 立即暂停音频并静音，避免重音
          if (audioPlayer.value) {
            audioPlayer.value.volume = 0;
            audioPlayer.value.pause();
          }
        }
      })
      
      navigator.mediaSession.setActionHandler('previoustrack', () => {
        playPrevious()
      })
      
      navigator.mediaSession.setActionHandler('nexttrack', () => {
        playNext()
      })
      
      navigator.mediaSession.setActionHandler('seekbackward', () => {
        if (audioPlayer.value) {
          audioPlayer.value.currentTime = Math.max(audioPlayer.value.currentTime - 10, 0);
        }
      })
      
      navigator.mediaSession.setActionHandler('seekforward', () => {
        if (audioPlayer.value) {
          audioPlayer.value.currentTime = Math.min(audioPlayer.value.currentTime + 10, audioPlayer.value.duration);
        }
      })
      
      // 设置当前播放的音乐元数据
      if (music || currentMusic.value) {
        updateMediaSessionMetadata(music || currentMusic.value)
      }
    } catch (error) {
      console.log('媒体会话API初始化失败:', error)
    }
  }
}

// 更新媒体会话元数据
const updateMediaSessionMetadata = (music) => {
  if ('mediaSession' in navigator && music) {
    try {
      const artwork = [
        { src: getCoverUrl(music.id), sizes: '96x96', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '128x128', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '192x192', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '256x256', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '384x384', type: 'image/jpeg' },
        { src: getCoverUrl(music.id), sizes: '512x512', type: 'image/jpeg' }
      ]
      
      navigator.mediaSession.metadata = new MediaMetadata({
        title: music.title || '未知标题',
        artist: music.artist || '未知艺术家',
        album: music.album || '未知专辑',
        artwork: artwork
      })
      
      // 更新播放状态
      navigator.mediaSession.playbackState = isPlaying.value ? 'playing' : 'paused'
    } catch (error) {
      console.log('更新媒体会话元数据失败:', error)
    }
  }
}

// 更新媒体会话播放状态
const updateMediaSessionPlaybackState = () => {
  if ('mediaSession' in navigator) {
    try {
      navigator.mediaSession.playbackState = isPlaying.value ? 'playing' : 'paused'
    } catch (error) {
      console.log('更新媒体会话播放状态失败:', error)
    }
  }
}

// 更新媒体会话播放位置
const updateMediaSessionPositionState = () => {
  if ('mediaSession' in navigator && 'setPositionState' in navigator.mediaSession) {
    try {
      navigator.mediaSession.setPositionState({
        duration: duration.value,
        playbackRate: 1.0,
        position: currentTime.value
      });
    } catch (error) {
      console.log('更新媒体会话播放位置失败:', error)
    }
  }
}

// 组件卸载时移除事件监听
onUnmounted(() => {
  window.removeEventListener('storage', handleStorageChange)
  window.removeEventListener('playerStateChange', handlePlayerStateChange)
  
  // 清除媒体会话
  if ('mediaSession' in navigator) {
    navigator.mediaSession.metadata = null
    navigator.mediaSession.playbackState = 'none'
  }
})
</script>

<style scoped>
.global-player {
  position: fixed;
  bottom: 0;
  left: 0;
  right: 0;
  height: 80px;
  width: 100%;
  background: rgba(255, 255, 255, 0.9);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-top: 1px solid rgba(255, 255, 255, 0.3);
  padding: 10px 20px;
  box-shadow: 0 -2px 20px rgba(0, 0, 0, 0.1);
  z-index: 1000;
  transition: transform 0.3s ease;
  display: flex;
  flex-direction: row;
  align-items: center;
  gap: 15px;
}

.player-content {
  display: flex;
  flex-direction: row;
  align-items: center;
  height: 100%;
  width: 100%;
  gap: 15px;
}

.cover-container {
  flex-shrink: 0;
}

.music-cover {
  width: 60px;
  height: 60px;
  object-fit: cover;
  border-radius: 6px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.15);
}

.placeholder-cover {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #6a5acd, #9370db);
  color: white;
  font-size: 1.2rem;
}

.music-info {
  flex-grow: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  justify-content: center;
  max-width: 200px;
}

.music-title {
  font-weight: bold;
  color: #5c4b7b;
  font-size: 0.9rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  margin-bottom: 2px;
}

.placeholder-text {
  color: #aaa;
}

.music-artist {
  color: #9370db;
  font-size: 0.75rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.player-controls {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-grow: 1;
  min-width: 200px;
  max-width: 600px;
}

.progress-container {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-grow: 1;
  min-width: 150px;
}

.time {
  color: #5c4b7b;
  font-size: 0.8rem;
  font-weight: bold;
  min-width: 40px;
  text-align: center;
}

.progress-bar {
  flex: 1;
  height: 5px;
  border-radius: 3px;
  background: rgba(106, 90, 205, 0.2);
  outline: none;
  -webkit-appearance: none;
}

.progress-bar::-webkit-slider-thumb {
  -webkit-appearance: none;
  width: 14px;
  height: 14px;
  border-radius: 50%;
  background: #6a5acd;
  cursor: pointer;
}

.control-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.prev-btn, .next-btn, .mode-btn, .playlist-btn {
  width: 32px;
  height: 32px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.9), rgba(138, 43, 226, 0.9));
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 8px rgba(106, 90, 205, 0.4);
  transition: all 0.3s ease;
  padding: 4px;
}

.prev-btn:hover, .next-btn:hover, .mode-btn:hover, .playlist-btn:hover {
  transform: scale(1.05);
  box-shadow: 0 6px 12px rgba(106, 90, 205, 0.6);
}

.prev-btn:disabled, .next-btn:disabled, .play-pause-btn:disabled {
  opacity: 0.5;
  cursor: not-allowed;
}

.play-pause-btn {
  width: 40px;
  height: 40px;
  border-radius: 50%;
  border: none;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.9), rgba(138, 43, 226, 0.9));
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  box-shadow: 0 4px 12px rgba(106, 90, 205, 0.4);
  transition: all 0.3s ease;
  padding: 6px;
}

/* SVG图标样式 */
.btn-icon {
  width: 100%;
  height: 100%;
  object-fit: contain;
  fill: white; /* SVG图标填充颜色为白色 */
}

/* 歌词显示区域 */
.lyrics-container {
  flex: 1;
  min-width: 150px;
  max-width: 350px;
  height: 100%;
  overflow: hidden;
  display: flex;
  align-items: center;
  padding-left: 20px;
  border-left: 1px solid #eee;
  margin-left: 10px;
}

.lyrics-content {
  width: 100%;
  height: 100%;
  display: flex;
  flex-direction: column;
  justify-content: center;
  align-items: flex-start;
  gap: 6px;
  overflow: hidden;
  position: relative;
}

.lyric-line {
  color: #888;
  font-size: 0.85rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  width: 100%;
  text-align: left;
  line-height: 1.5;
  opacity: 0.5;
  position: relative;
  transition: opacity 0.4s ease;
}

.lyric-line.active {
  color: #6a5acd;
  font-weight: 700;
  font-size: 1rem;
  opacity: 1;
  text-shadow: 0 0 10px rgba(106, 90, 205, 0.5);
}

/* 滚动进入动画 */
@keyframes scrollIn {
  0% {
    transform: translateX(20px);
    opacity: 0;
  }
  100% {
    transform: translateX(0);
    opacity: 1;
  }
}

/* 当歌词变成活动状态时的滚动动画 */
.lyric-line.active-enter {
  animation: scrollIn 0.6s ease-out;
}

/* 播放列表 */
.playlist-container {
  position: fixed;
  bottom: 80px;
  right: 20px;
  width: 350px;
  height: 400px;
  background: rgba(255, 255, 255, 0.95);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  border-radius: 12px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.2);
  z-index: 1001;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid rgba(255, 255, 255, 0.3);
}

.playlist-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 15px;
  background: linear-gradient(135deg, rgba(106, 90, 205, 0.9), rgba(138, 43, 226, 0.9));
  color: white;
}

.playlist-header h3 {
  margin: 0;
  font-size: 1rem;
}

.close-playlist {
  background: none;
  border: none;
  color: white;
  font-size: 1.2rem;
  cursor: pointer;
  width: 30px;
  height: 30px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.3s;
}

.close-playlist:hover {
  background: rgba(255, 255, 255, 0.2);
}

.playlist-items {
  flex: 1;
  overflow-y: auto;
  padding: 10px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.playlist-item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.3s;
  border: 1px solid #eee;
}

.playlist-item:hover {
  background: rgba(106, 90, 205, 0.1);
}

.playlist-item.current {
  background: rgba(106, 90, 205, 0.2);
  border-color: #6a5acd;
}

.playlist-item-info {
  flex: 1;
  display: flex;
  flex-direction: column;
}

.playlist-item-title {
  font-weight: bold;
  color: #333;
  font-size: 0.9rem;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.playlist-item-artist {
  font-size: 0.8rem;
  color: #666;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.current-indicator {
  color: #6a5acd;
  font-weight: bold;
  margin-left: 10px;
}

/* 隐藏audio元素 */
audio {
  display: none;
}

/* 响应式设计 */
@media (max-width: 768px) {
  .global-player {
    height: 70px;
    padding: 8px 15px;
  }
  
  .player-content {
    gap: 10px;
  }
  
  .music-cover {
    width: 50px;
    height: 50px;
  }
  
  .music-info {
    max-width: 120px;
  }
  
  .music-title {
    font-size: 0.8rem;
  }
  
  .music-artist {
    font-size: 0.7rem;
  }
  
  .player-controls {
    max-width: 400px;
  }
  
  .lyrics-container {
    display: none; /* 在小屏幕上隐藏歌词 */
  }
  
  .prev-btn, .next-btn, .mode-btn, .playlist-btn {
    width: 30px;
    height: 30px;
  }
  
  .play-pause-btn {
    width: 36px;
    height: 36px;
  }
  
  .time {
    font-size: 0.7rem;
    min-width: 30px;
  }
  
  .progress-bar {
    height: 4px;
  }
  
  .progress-bar::-webkit-slider-thumb {
    width: 12px;
    height: 12px;
  }
}
</style>