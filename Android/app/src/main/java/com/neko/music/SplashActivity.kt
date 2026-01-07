package com.neko.music

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.neko.music.ui.theme.NetEaseRed
import com.neko.music.ui.theme.Neko云音乐Theme
import kotlinx.coroutines.delay

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        // 设置窗口背景为红色渐变，避免白屏
        window.setBackgroundDrawableResource(android.R.color.transparent)
        
        setContent {
            Neko云音乐Theme {
                SplashScreen(
                    onAnimationComplete = {
                        startActivity(Intent(this@SplashActivity, MainActivity::class.java))
                        finish()
                    }
                )
            }
        }
    }
}

@Composable
fun SplashScreen(onAnimationComplete: () -> Unit) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        scale.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800, delayMillis = 100)
        )
        alpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800, delayMillis = 100)
        )
        delay(1500)
        onAnimationComplete()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(
                        NetEaseRed,
                        Color(0xFFE63946)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LogoIcon(
                scale = scale.value,
                alpha = alpha.value
            )

            Spacer(modifier = Modifier.height(32.dp))

            AppTitle(
                alpha = alpha.value
            )

            Spacer(modifier = Modifier.height(16.dp))

            AppSubtitle(
                alpha = alpha.value
            )
        }
    }
}

@Composable
fun LogoIcon(scale: Float, alpha: Float) {
    Box(
        modifier = Modifier
            .size(120.dp)
            .scale(scale)
            .alpha(alpha)
            .background(
                color = Color.White,
                shape = androidx.compose.foundation.shape.CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "🎵",
            fontSize = 64.sp,
            modifier = Modifier.alpha(alpha)
        )
    }
}

@Composable
fun AppTitle(alpha: Float) {
    Text(
        text = "Neko云音乐",
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = Modifier.alpha(alpha)
    )
}

@Composable
fun AppSubtitle(alpha: Float) {
    Text(
        text = "听见好时光",
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        color = Color.White.copy(alpha = 0.8f),
        textAlign = TextAlign.Center,
        modifier = Modifier.alpha(alpha)
    )
}

@Preview(showBackground = true)
@Composable
fun SplashScreenPreview() {
    Neko云音乐Theme {
        SplashScreen(onAnimationComplete = {})
    }
}