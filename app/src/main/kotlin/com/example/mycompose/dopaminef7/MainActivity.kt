package com.example.mycompose.dopaminef7

import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import android.os.IInterface
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Locale
import kotlin.system.exitProcess

interface IShellService : IInterface {
    fun destroy()
    fun runCommand(command: String): String

    companion object {
        const val DESCRIPTOR = "com.example.mycompose.dopaminef7.IShellService"
        const val TRANSACTION_DESTROY = 16777114
        const val TRANSACTION_RUN_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 0

        fun asInterface(obj: IBinder?): IShellService? {
            if (obj == null) return null
            val iin = obj.queryLocalInterface(DESCRIPTOR)
            return if (iin != null && iin is IShellService) iin else Proxy(obj)
        }
    }

    private class Proxy(private val remote: IBinder) : IShellService {
        override fun asBinder(): IBinder = remote

        override fun destroy() {
            val data = Parcel.obtain()
            try {
                data.writeInterfaceToken(DESCRIPTOR)
                remote.transact(TRANSACTION_DESTROY, data, null, IBinder.FLAG_ONEWAY)
            } finally {
                data.recycle()
            }
        }

        override fun runCommand(command: String): String {
            val data = Parcel.obtain()
            val reply = Parcel.obtain()
            return try {
                data.writeInterfaceToken(DESCRIPTOR)
                data.writeString(command)
                remote.transact(TRANSACTION_RUN_COMMAND, data, reply, 0)
                reply.readException()
                reply.readString() ?: "[FAILED] Empty reply"
            } finally {
                reply.recycle()
                data.recycle()
            }
        }
    }

    abstract class Stub : Binder(), IShellService {
        init {
            attachInterface(this, DESCRIPTOR)
        }

        override fun asBinder(): IBinder = this

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            return when (code) {
                IBinder.INTERFACE_TRANSACTION -> {
                    reply?.writeString(DESCRIPTOR)
                    true
                }
                TRANSACTION_DESTROY -> {
                    data.enforceInterface(DESCRIPTOR)
                    destroy()
                    true
                }
                TRANSACTION_RUN_COMMAND -> {
                    data.enforceInterface(DESCRIPTOR)
                    val cmd = data.readString() ?: ""
                    val res = runCommand(cmd)
                    reply?.writeNoException()
                    reply?.writeString(res)
                    true
                }
                else -> super.onTransact(code, data, reply, flags)
            }
        }
    }
}

class ShellService : IShellService.Stub() {
    override fun destroy() {
        exitProcess(0)
    }

    override fun runCommand(command: String): String {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val out = BufferedReader(InputStreamReader(process.inputStream)).readText().trim()
            val err = BufferedReader(InputStreamReader(process.errorStream)).readText().trim()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val msg = out.ifEmpty { "Applied" }
                "[SUCCESS] $msg"
            } else {
                val reason = err.ifEmpty { out.ifEmpty { "Exit code $exitCode" } }
                "[FAILED] $reason"
            }
        } catch (e: Exception) {
            "[FAILED] ${e.message}"
        }
    }
}

enum class DopamineTab {
    DASHBOARD, MONITOR, BATTERY, APPS, LOGS, TWEAKS
}

data class AppItem(val name: String, val packageName: String, val enabled: Boolean)

class MainActivity : ComponentActivity() {

    private var isReady by mutableStateOf(false)
    private var logText by mutableStateOf("> Dopamine v3.0 initialized...\n")
    private var shellService: IShellService? = null

    private var cpuLoad by mutableStateOf("--%")
    private var ramUsed by mutableStateOf("-- GB")
    private var cpuTemp by mutableStateOf("-- °C")
    private var cpuFreq by mutableStateOf("-- MHz")

    private var prevIdle = 0L
    private var prevTotal = 0L

    private val userServiceArgs = Shizuku.UserServiceArgs(
        ComponentName("com.example.mycompose.dopaminef7", ShellService::class.java.name)
    )
        .daemon(false)
        .processNameSuffix("shell_service")
        .debuggable(false)
        .version(2)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            shellService = IShellService.asInterface(service)
            isReady = true
            log("[SUCCESS] System service bound.")
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellService = null
            isReady = false
            log("[WARN] System service disconnected.")
        }
    }

    private val permissionListener = Shizuku.OnRequestPermissionResultListener { _, grantResult ->
        if (grantResult == PackageManager.PERMISSION_GRANTED) {
            bindShellService()
        } else {
            isReady = false
            log("[FAILED] Shizuku permission rejected.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Shizuku.addRequestPermissionResultListener(permissionListener)
        checkShizuku()

        setContent {
            DopamineApp()
        }
    }

    @Composable
    fun DopamineApp() {
        var currentTab by remember { mutableStateOf(DopamineTab.DASHBOARD) }

        LaunchedEffect(isReady) {
            while (true) {
                updateRam()
                if (isReady && shellService != null) {
                    updateCpuAndThermal()
                }
                delay(1500)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF060709))
                .padding(horizontal = 20.dp, vertical = 16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "DOPAMINE",
                        color = Color.White,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 2.sp
                    )
                    Text(
                        text = "POCO F7 // HYPEROS",
                        color = Color(0xFF6B7280),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                Box(
                    modifier = Modifier
                        .background(
                            if (isReady) Color(0xFF0D2818) else Color(0xFF2A1215),
                            RoundedCornerShape(6.dp)
                        )
                        .border(
                            1.dp,
                            if (isReady) Color(0xFF1E5631) else Color(0xFF5C2126),
                            RoundedCornerShape(6.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    Text(
                        text = if (isReady) "SYSTEM READY" else "OFFLINE",
                        color = if (isReady) Color(0xFF4ADE80) else Color(0xFFF87171),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            HorizontalDivider(color = Color(0xFF1A1D24), modifier = Modifier.padding(vertical = 16.dp))

            Row(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("CPU LOAD", color = Color(0xFF6B7280), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                    Text(
                        text = cpuLoad,
                        color = Color(0xFFFF5500),
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
)
}
Column(modifier = Modifier.weight(1f)) {
Text("RAM USED", color = Color(0xFF6B7280), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
Text(
text = ramUsed,
color = Color.White,
fontSize = 30.sp,
fontWeight = FontWeight.Bold,
modifier = Modifier.padding(top = 4.dp)
)
}
}
HorizontalDivider(color = Color(0xFF1A1D24), modifier = Modifier.padding(vertical = 16.dp))
Row(
modifier = Modifier.fillMaxWidth(),
horizontalArrangement = Arrangement.SpaceBetween,
verticalAlignment = Alignment.CenterVertically
) {
Text(
text = if (currentTab == DopamineTab.DASHBOARD) "SYSTEM TOOLS" else "← BACK TO MENU",
color = if (currentTab == DopamineTab.DASHBOARD) Color.White else Color(0xFFFF5500),
fontSize = 14.sp,
fontWeight = FontWeight.Bold,
letterSpacing = 1.sp,
modifier = Modifier.clickable { currentTab = DopamineTab.DASHBOARD }
)
Box(
modifier = Modifier
.border(1.dp, Color(0xFFFF5500), RoundedCornerShape(4.dp))
.clickable { currentTab = DopamineTab.LOGS }
.padding(horizontal = 6.dp, vertical = 2.dp)
) {
Text(">_", color = Color(0xFFFF5500), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
}
}
Spacer(modifier = Modifier.height(14.dp))
when (currentTab) {
DopamineTab.DASHBOARD -> DashboardMenu(onSelect = { currentTab = it })
DopamineTab.MONITOR -> MonitorScreen()
DopamineTab.BATTERY -> BatteryScreen()
DopamineTab.APPS -> AppManagerScreen()
DopamineTab.TWEAKS -> TweaksScreen()
DopamineTab.LOGS -> LogViewerScreen()
}
}
}
@Composable
fun DashboardMenu(onSelect: (DopamineTab) -> Unit) {
Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
MenuRow("[~]", "Мониторинг системы", "Температура, частоты CPU/GPU") { onSelect(DopamineTab.MONITOR) }
MenuRow("[#]", "System Tweaks & Debloat", "Рекомендации, 120 Гц, Restore Point") { onSelect(DopamineTab.TWEAKS) }
MenuRow("[+]", "Battery Care", "Вольтаж, температура и ресурс АКБ") { onSelect(DopamineTab.BATTERY) }
MenuRow("[A]", "App Manager", "Управление пакетами и заморозка") { onSelect(DopamineTab.APPS) }
MenuRow("[=]", "Log Viewer", "Терминал вывода команд") { onSelect(DopamineTab.LOGS) }
}
}
@Composable
fun MenuRow(badge: String, title: String, subtitle: String, onClick: () -> Unit) {
Row(
verticalAlignment = Alignment.CenterVertically,
modifier = Modifier
.fillMaxWidth()
.clickable(onClick = onClick)
.padding(vertical = 12.dp)
) {
Text(
text = badge,
color = Color(0xFFFF5500),
fontFamily = FontFamily.Monospace,
fontWeight = FontWeight.Bold,
fontSize = 16.sp,
modifier = Modifier.width(38.dp)
)
Column {
Text(text = title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Medium)
Text(text = subtitle, color = Color(0xFF6B7280), fontSize = 12.sp)
}
}
}
@Composable
fun MonitorScreen() {
Column(modifier = Modifier.fillMaxWidth()) {
InfoCard("CPU TEMPERATURE", cpuTemp)
InfoCard("CPU CURRENT FREQ (BIG CORE)", cpuFreq)
InfoCard("LOAD / MEMORY", "$cpuLoad  //  $ramUsed")
}
}
@Composable
fun BatteryScreen() {
val intent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
val temp = (intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
val voltage = (intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) ?: 0) / 1000f
Column(modifier = Modifier.fillMaxWidth()) {
InfoCard("BATTERY LEVEL", "$level %")
InfoCard("BATTERY TEMPERATURE", "$temp °C")
InfoCard("VOLTAGE", String.format(Locale.US, "%.2f V", voltage))
}
}
@Composable
fun TweaksScreen() {
Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
ActionBtn("[ RESTORE POINT ] Создать точку отката") {
log("\n--- Creating Restore Point ---")
exec("settings get global window_animation_scale > /sdcard/dopamine_backup.txt")
exec("settings get system min_refresh_rate >> /sdcard/dopamine_backup.txt")
log("[SUCCESS] Backup saved to /sdcard/dopamine_backup.txt")
}
ActionBtn("[ ROLLBACK ] Восстановить сток (1.0x / Auto Hz)") {
log("\n--- Restoring Default Settings ---")
exec("settings put global window_animation_scale 1.0")
exec("settings put global transition_animation_scale 1.0")
exec("settings put global animator_duration_scale 1.0")
exec("settings delete system min_refresh_rate")
}
ActionBtn("[ DEBLOAT ] Отключить системные рекомендации") {
log("\n--- Disabling System Ads & Telemetry ---")
listOf("com.miui.msa.global", "com.miui.analytics", "com.miui.daemon").forEach {
exec("pm uninstall --user 0 $it")
}
}
ActionBtn("[ DISPLAY ] Принудительные 120 Гц + 0.5x") {
log("\n--- Setting 120Hz Refresh Rate ---")
exec("settings put system user_refresh_rate 120")
exec("settings put system min_refresh_rate 120")
exec("settings put global window_animation_scale 0.5")
exec("settings put global transition_animation_scale 0.5")
}
}
}
@Composable
fun AppManagerScreen() {
val apps = remember { mutableStateListOf<AppItem>() }
LaunchedEffect(Unit) {
val pm = packageManager
val list = pm.getInstalledApplications(PackageManager.GET_META_DATA)
.filter { (it.flags and ApplicationInfo.FLAG_SYSTEM) == 0 }
.map {
AppItem(
name = pm.getApplicationLabel(it).toString(),
packageName = it.packageName,
enabled = it.enabled
)
}
.sortedBy { it.name.lowercase() }
apps.clear()
apps.addAll(list)
}
LazyColumn(modifier = Modifier.fillMaxSize()) {
items(apps) { app ->
Row(
modifier = Modifier
.fillMaxWidth()
.padding(vertical = 8.dp),
horizontalArrangement = Arrangement.SpaceBetween,
verticalAlignment = Alignment.CenterVertically
) {
Column(modifier = Modifier.weight(1f)) {
Text(app.name, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
Text(app.packageName, color = Color(0xFF6B7280), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
}
TextButton(onClick = {
val cmd = if (app.enabled) "pm disable-user --user 0 ${app.packageName}" else "pm enable ${app.packageName}"
exec(cmd)
}) {
Text(
text = if (app.enabled) "FREEZE" else "ENABLE",
color = Color(0xFFFF5500),
fontFamily = FontFamily.Monospace,
fontSize = 12.sp
)
}
}
}
}
}
@Composable
fun LogViewerScreen() {
Box(
modifier = Modifier
.fillMaxSize()
.background(Color(0xFF0A0C10), RoundedCornerShape(8.dp))
.border(1.dp, Color(0xFF1A1D24), RoundedCornerShape(8.dp))
.padding(12.dp)
.verticalScroll(rememberScrollState())
) {
Text(
text = logText,
color = Color(0xFF4ADE80),
fontFamily = FontFamily.Monospace,
fontSize = 12.sp
)
}
}
@Composable
fun InfoCard(label: String, value: String) {
Column(
modifier = Modifier
.fillMaxWidth()
.padding(bottom = 10.dp)
.background(Color(0xFF0D0F14), RoundedCornerShape(8.dp))
.border(1.dp, Color(0xFF1A1D24), RoundedCornerShape(8.dp))
.padding(14.dp)
) {
Text(label, color = Color(0xFF6B7280), fontSize = 11.sp, fontFamily = FontFamily.Monospace)
Text(value, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
}
}
@Composable
fun ActionBtn(title: String, onClick: () -> Unit) {
Button(
onClick = onClick,
colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF11141B)),
shape = RoundedCornerShape(8.dp),
contentPadding = PaddingValues(horizontal = 14.dp, vertical = 14.dp),
modifier = Modifier
.fillMaxWidth()
.defaultMinSize(minHeight = 48.dp)
) {
Text(
text = title,
color = Color.White,
fontFamily = FontFamily.Monospace,
fontSize = 13.sp,
modifier = Modifier.fillMaxWidth()
)
}
Spacer(modifier = Modifier.height(10.dp))
}
private fun updateRam() {
val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
val memInfo = ActivityManager.MemoryInfo()
am.getMemoryInfo(memInfo)
val usedBytes = memInfo.totalMem - memInfo.availMem
val usedGb = usedBytes / (1024.0 * 1024.0 * 1024.0)
ramUsed = String.format(Locale.US, "%.1f GB", usedGb)
}
private fun updateCpuAndThermal() {
val service = shellService ?: return
Thread {
try {
val statRes = service.runCommand("head -n 1 /proc/stat").removePrefix("[SUCCESS] ").trim()
val parts = statRes.split(' ').filter { it.isNotEmpty() }
if (parts.size >= 5 && parts.first() == "cpu") {
val idle = parts.getOrNull(4)?.toLongOrNull() ?: 0L
val total = parts.drop(1).take(7).sumOf { it.toLongOrNull() ?: 0L }
if (prevTotal > 0 && total > prevTotal) {
val diffIdle = idle - prevIdle
val diffTotal = total - prevTotal
val usage = ((diffTotal - diffIdle) * 100 / diffTotal).coerceIn(1, 100)
runOnUiThread { cpuLoad = "$usage%" }
}
prevIdle = idle
prevTotal = total
}
val tempRaw = service.runCommand("cat /sys/class/thermal/thermal_zone0/temp").removePrefix("[SUCCESS] ").trim()
val tempVal = tempRaw.toFloatOrNull()
if (tempVal != null) {
val celsius = if (tempVal > 1000) tempVal / 1000f else tempVal
runOnUiThread { cpuTemp = String.format(Locale.US, "%.1f °C", celsius) }
}
val freqRaw = service.runCommand("cat /sys/devices/system/cpu/cpu7/cpufreq/scaling_cur_freq").removePrefix("[SUCCESS] ").trim()
val freqKhz = freqRaw.toLongOrNull()
if (freqKhz != null) {
runOnUiThread { cpuFreq = "${freqKhz / 1000} MHz" }
}
} catch (_: Exception) {
}
}.start()
}
private fun checkShizuku() {
try {
if (Shizuku.pingBinder()) {
if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
bindShellService()
} else {
Shizuku.requestPermission(0)
}
} else {
isReady = false
log("[FAILED] Shizuku service offline.")
}
} catch (e: Exception) {
log("[FAILED] ${e.message}")
}
}
private fun bindShellService() {
try {
Shizuku.bindUserService(userServiceArgs, serviceConnection)
} catch (e: Exception) {
log("[FAILED] Bind error: ${e.message}")
}
}
private fun exec(command: String) {
val service = shellService
if (service == null) {
log("> $command -> [FAILED] Service not bound")
return
}
Thread {
val result = try {
service.runCommand(command)
} catch (e: Exception) {
"[FAILED] ${e.message}"
}
runOnUiThread {
log("> $command\n  $result")
}
}.start()
}
private fun log(text: String) {
logText += "$text\n"
}
override fun onDestroy() {
super.onDestroy()
Shizuku.removeRequestPermissionResultListener(permissionListener)
try {
Shizuku.unbindUserService(userServiceArgs, serviceConnection, true)
} catch (_: Exception) {
}
}
}