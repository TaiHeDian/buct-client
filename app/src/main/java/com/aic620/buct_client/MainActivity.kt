package com.aic620.buct_client

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.aic620.buct_client.ui.theme.BUCTClientTheme

// MainActivity.kt
class MainActivity : ComponentActivity() {
    private val viewModel: PressureViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            PressureMonitorTheme {
                PressureMonitorScreen(viewModel)
            }
        }
    }
}

// PressureViewModel.kt
class PressureViewModel : ViewModel() {
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _pressureData = MutableStateFlow<List<Float>>(emptyList())
    val pressureData: StateFlow<List<Float>> = _pressureData.asStateFlow()

    private val _currentValue = MutableStateFlow<Float>(0f)
    val currentValue: StateFlow<Float> = _currentValue.asStateFlow()

    private var socket: Socket? = null
    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val dataQueue = ArrayDeque<Float>(5000) // 对应原PyQt项目的data_deque

    fun connectToDevice(ipAddress: String) {
        scope.launch {
            try {
                _connectionState.value = ConnectionState.Connecting
                socket = Socket(ipAddress, 9000) // 使用原项目的端口9000
                _connectionState.value = ConnectionState.Connected
                startReceiving()
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(e.message ?: "连接失败")
            }
        }
    }

    private fun startReceiving() {
        scope.launch {
            try {
                val buffer = ByteArray(20) // 10个数据点 * 2字节
                while (isActive) {
                    val inputStream = socket?.getInputStream()
                    val bytesRead = inputStream?.read(buffer)

                    if (bytesRead == 20) {
                        val newData = processRawData(buffer)
                        updateData(newData)
                    }
                }
            } catch (e: Exception) {
                _connectionState.value = ConnectionState.Error(e.message ?: "接收数据失败")
            }
        }
    }

    private fun processRawData(buffer: ByteArray): List<Float> {
        return buffer.chunked(2).map { bytes ->
            val rawValue = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
            // 使用原项目的转换公式
            ((1 - rawValue / 4095f) * 3.3f / 10000f) * 2.8889f * 1000000f + 17.0411f
        }
    }

    private fun updateData(newData: List<Float>) {
        newData.forEach { value ->
            dataQueue.addLast(value)
            if (dataQueue.size > 5000) {
                dataQueue.removeFirst()
            }
        }

        _currentValue.value = newData.last()
        _pressureData.value = dataQueue.toList()

        // 保存数据到文件
        saveDataToFile(newData)
    }

    private fun saveDataToFile(data: List<Float>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val filename = generateFilename()
                context.openFileOutput(filename, Context.MODE_APPEND).use { output ->
                    data.forEach { value ->
                        output.write("$value\n".toByteArray())
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun generateFilename(): String {
        val now = LocalDateTime.now()
        return now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH.mm.ss")) + ".csv"
    }

    // 对应原项目的moving_average函数
    private fun movingAverage(data: List<Float>, windowSize: Int): List<Float> {
        if (data.size < windowSize) return data
        return data.windowed(windowSize) { window ->
            window.average().toFloat()
        }
    }

    fun disconnect() {
        socket?.close()
        socket = null
        _connectionState.value = ConnectionState.Disconnected
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
        scope.cancel()
    }
}

@Composable
fun PressureMonitorScreen(viewModel: PressureViewModel) {
    var ipAddress by remember { mutableStateOf("") }
    val pressureData by viewModel.pressureData.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val currentValue by viewModel.currentValue.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // 顶部控制栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = ipAddress,
                onValueChange = { ipAddress = it },
                label = { Text("设备IP地址") },
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Button(
                onClick = {
                    if (ipAddress.isNotBlank()) {
                        viewModel.connectToDevice(ipAddress)
                    }
                },
                enabled = connectionState !is ConnectionState.Connected
            ) {
                Text("连接设备")
            }

            Spacer(modifier = Modifier.width(8.dp))

            Button(
                onClick = { viewModel.disconnect() },
                enabled = connectionState is ConnectionState.Connected
            ) {
                Text("停止")
            }
        }

        // 状态显示
        Text(
            text = when (connectionState) {
                ConnectionState.Connected -> "已连接"
                ConnectionState.Connecting -> "连接中..."
                ConnectionState.Disconnected -> "未连接"
                is ConnectionState.Error -> "错误: ${(connectionState as ConnectionState.Error).message}"
            },
            modifier = Modifier.padding(vertical = 8.dp)
        )

        // 当前值显示
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "当前压力值",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${String.format("%.2f", currentValue)} KPa",
                    style = MaterialTheme.typography.headlineLarge
                )
            }
        }

        // 压力图表
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp)
        ) {
            PressureChart(
                data = pressureData,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(8.dp)
            )
        }

        // 底部按钮
        Button(
            onClick = { /* 显示历史数据 */ },
            modifier = Modifier.align(Alignment.CenterHorizontally)
        ) {
            Text("查看历史数据")
        }
    }
}

@Composable
fun PressureChart(
    data: List<Float>,
    modifier: Modifier = Modifier
) {
    // 使用 Recharts 创建图表
    val chartData = data.mapIndexed { index, value ->
        mapOf(
            "index" to index,
            "value" to value
        )
    }

    LineChart(
        width = 800,
        height = 400
    ) {
        Line(
            type = "monotone",
            dataKey = "value",
            stroke = "#8884d8",
            dot = false
        )
        XAxis(dataKey = "index")
        YAxis()
        Tooltip()
    }
}

sealed class ConnectionState {
    object Connected : ConnectionState()
    object Connecting : ConnectionState()
    object Disconnected : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}
