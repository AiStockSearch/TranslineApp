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
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import kotlinx.coroutines.launch
import org.transline.geoworker.tracker.*
import kotlinx.datetime.Clock

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        val storage = SharedPreferencesTrackingStorage(applicationContext)
        val secureStore = EncryptedPrefsSecureConfigStore(applicationContext)
        val realLocationProvider = AndroidLocationProvider(applicationContext)
        val httpClient = HttpClient(OkHttp)
        val networkChecker = AndroidNetworkChecker(applicationContext)
        val locationRepository = DefaultLocationRepository(httpClient, storage, networkChecker)
        val controller = LocationTrackerController(realLocationProvider, locationRepository, storage, secureStore)

        setContent {
            MaterialTheme {
                var hasPermissions by remember { mutableStateOf(false) }

                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestMultiplePermissions()
                ) { permissions ->
                    hasPermissions = permissions.values.any { it }
                }

                LaunchedEffect(Unit) {
                    permissionLauncher.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION,
                            Manifest.permission.POST_NOTIFICATIONS
                        )
                    )
                }

                if (hasPermissions) {
                    TestTrackerScreen(controller, applicationContext)
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
fun TestTrackerScreen(
    controller: LocationTrackerController,
    appContext: android.content.Context
) {
    var statusText by remember { mutableStateOf("Нажми обновить статус") }
    val scope = rememberCoroutineScope()

    val refreshStatus = {
        val state = controller.getScheduleState()
        statusText = """
            Активен: ${state.isTrackingActive}
            Running: ${controller.isLocationServiceRunning()}
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
        Text(
            "Тест модуля (Logcat: TrackerTest / LocationFGS)",
            color = MaterialTheme.colorScheme.primary
        )
        Spacer(modifier = Modifier.height(16.dp))

        Text(statusText)
        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            Log.d("TrackerTest", "--- CONTINUOUS startLocationService ---")
            controller.startLocationService(
                apiEndpoint = "https://example.com",
                driverUuid = "demo-driver-uuid",
                orderNumber = "ORD-DEMO",
                updateIntervalMinutes = 1
            )
            LocationForegroundService.start(appContext)
            refreshStatus()
        }) {
            Text("Continuous: startLocationService + FGS")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            Log.d("TrackerTest", "--- stopLocationService ---")
            controller.stopLocationService()
            LocationForegroundService.stop(appContext)
            refreshStatus()
        }) {
            Text("Continuous: stopLocationService")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            Log.d("TrackerTest", "--- СТАРТ РЕЙСА ---")
            controller.startTrip(Clock.System.now().toEpochMilliseconds())
            LocationForegroundService.start(appContext)
            refreshStatus()
        }) {
            Text("Рейс: startTrip")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = {
            Log.d("TrackerTest", "--- ПРОВЕРКА ОТПРАВКИ ---")
            scope.launch {
                controller.executePendingOrScheduledTracking()
                refreshStatus()
            }
        }) {
            Text("Рейс: executePendingOrScheduledTracking")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(onClick = { refreshStatus() }) {
            Text("Обновить статус")
        }
    }
}
