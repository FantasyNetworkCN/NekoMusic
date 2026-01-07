package com.neko.music

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.neko.music.data.model.Music
import com.neko.music.ui.components.BottomNavigationBar
import com.neko.music.ui.components.BottomNavItem
import com.neko.music.ui.screens.HomeScreen
import com.neko.music.ui.screens.MineScreen
import com.neko.music.ui.screens.SearchResultScreen
import com.neko.music.ui.theme.Neko云音乐Theme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            Neko云音乐Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color.Transparent
                ) {
                    MainScreen()
                }
            }
        }
    }
}

@Composable
fun MainScreen() {
    val navController = rememberNavController()
    
    Box(modifier = Modifier.fillMaxSize()) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.fillMaxSize()
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxSize()
            ) {
                NavHost(
                    navController = navController,
                    startDestination = BottomNavItem.Home.route
                ) {
                    composable(BottomNavItem.Home.route) {
                        HomeScreen(
                            onSearchClick = {
                                Log.d("MainActivity", "导航到搜索页面")
                                navController.navigate("search")
                            }
                        )
                    }
                    composable(BottomNavItem.Mine.route) {
                        MineScreen()
                    }
                    composable(
                        route = "search?query={query}",
                        arguments = listOf(
                            navArgument("query") {
                                type = NavType.StringType
                                defaultValue = ""
                            }
                        )
                    ) { backStackEntry ->
                        val query = backStackEntry.arguments?.getString("query") ?: ""
                        Log.d("MainActivity", "搜索页面加载，查询: $query")
                        SearchResultScreen(
                            initialQuery = query,
                            onBackClick = {
                                Log.d("MainActivity", "从搜索页面返回")
                                navController.popBackStack()
                            },
                            onMusicClick = { music ->
                                Log.d("MainActivity", "点击音乐: ${music.title}")
                                // TODO: 处理音乐点击事件，跳转到播放页面
                            }
                        )
                    }
                }
            }
            
            BottomNavigationBar(navController = navController)
        }
    }
}