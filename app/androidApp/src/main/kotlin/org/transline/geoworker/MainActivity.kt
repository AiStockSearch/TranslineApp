package org.transline.geoworker

import android.Manifest
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.transline.geoworker.tracker.*
import kotlinx.datetime.Clock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val storage = SharedPreferencesTrackingStorage(applicationContext)

        // Инициализация реального GPS провайдера
        val realLocationProvider = AndroidLocationProvider(applicationContext)

        val dummyApiService = object : LocationApiService {
            override suspend fun sendLocation(location: Location): Boolean {
                Log.d("TrackerTest", "🌐 API: Отправка на сервер (${location.latitude}, ${location.longitude})... УСПЕШНО")
                return true 
            }
        }

        val networkChecker = AndroidNetworkChecker(applicationContext)
        val offlineQueue = StorageOfflineQueueStorage(storage)
        val locationRepository = LocationRepository(dummyApiService, networkChecker, offlineQueue)

        val controller = LocationTrackerController(realLocationProvider, locationRepository, storage)

        setContent {
            MaterialTheme {
                var hasPermissions by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasPermissions = permissions.values.all { it }
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }

                if (hasPermissions) {
                    TestTrackerScreen(controller)
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("Необходимы разрешения на геолокацию")
                    }
                }
            }
        }
    }
}

@Composable
fun TestTrackerScreen(controller: LocationTrackerController) {
    var statusText by remember { mutableStateOf("Нажми обновить статус") }
    val scope = rememberCoroutineScope()

    val refreshStatus = {
        val state = controller.getScheduleState()
        statusText = """
            Активен: ${state.isTrackingActive}
            Последняя отправка: ${state.lastSentTimestamp}
            Следующая плановая: ${state.nextScheduledTimestamp}
        """.trimIndent()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Тестирование модуля (смотри Logcat по тегу 'TrackerTest')", color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(statusText)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            Log.d("TrackerTest", "--- СТАРТ РЕЙСА ---")
            // Назначаем рейс. Время погрузки ставим = сейчас
            controller.startTrip(Clock.System.now().toEpochMilliseconds())
            refreshStatus()
        }) {
            Text("Начать рейс (startTrip)")
        }

        Button(onClick = {
            Log.d("TrackerTest", "--- ПРОВЕРКА ОТПРАВКИ (SIMULATE BOOT/TICK) ---")
            scope.launch {
                controller.executePendingOrScheduledTracking()
                refreshStatus()
            }
        }) {
            Text("Выполнить отправку (executePendingOrScheduledTracking)")
        }

        Button(onClick = { refreshStatus() }) {
            Text("Обновить статус")
        }
    }
}