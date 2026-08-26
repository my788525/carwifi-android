package com.carwifi.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.carwifi.app.data.AppSettings
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.ChannelType
import com.carwifi.app.util.NightModeManager
import java.util.UUID

private val BrandBlue = androidx.compose.ui.graphics.Color(0xFF1769FF)

@Composable
fun CarWifiTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = lightColorScheme(primary = BrandBlue),
        content = content
    )
}

private const val GUIDE_XIAOMI = "你的小米 / Redmi / POCO 手机系统已自带「自动任务」，可直接实现「充电时自动开启个人热点」，无需本应用接管：\n\n" +
    "设置 → 电池与性能 → 自动任务 → 新建 → 触发条件选「充电」→ 操作选「打开个人热点」。\n\n" +
    "如仍希望由本应用接管（通过 Shizuku 调用系统接口），请先在下方「Shizuku 授权」卡片完成授权，授权成功后充电即自动开热点。"

private const val GUIDE_OTHER = "本机系统未自带「充电自动开热点」功能，需通过 Shizuku 授权本应用调用系统热点接口：\n\n" +
    "1. 在手机上安装 Shizuku（GitHub / F-Droid 可获取）。\n" +
    "2. 打开 Shizuku，按提示开启「无线调试」：设置 → 关于手机 → 连续点击「版本号」开启开发者选项 → 开发者选项 → 无线调试，然后在 Shizuku 中启动。\n" +
    "3. 回到本应用，在下方「Shizuku 授权」卡片点「请求授权」，在 Shizuku 弹窗中允许。\n" +
    "4. 授权成功后，充电时本应用将自动开启 WiFi 热点；未授权时仅作提示，需手动开启。\n\n" +
    "（第 2 步路径因机型而异，请以实际系统为准。）"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    isXiaomi: Boolean,
    onPatch: (AppSettings.() -> AppSettings) -> Unit,
    shizukuReady: Boolean,
    onRequestShizuku: () -> Unit,
    onOpenNotifListener: () -> Unit,
    onOpenBatteryOpt: () -> Unit,
    batteryExempt: Boolean,
    onStartService: () -> Unit,
    onReplayQueued: () -> Unit,
    versionName: String,
    updateStatus: String,
    onCheckUpdate: () -> Unit,
    auditLines: List<String>,
    onRefreshAudit: () -> Unit
) {
    var showHotspotGuide by remember { mutableStateOf(false) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("CarWifi 设置") }) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("运行控制", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = onStartService, modifier = Modifier.fillMaxWidth()) {
                            Text("启动 / 重启核心服务")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(onClick = onOpenBatteryOpt, modifier = Modifier.fillMaxWidth()) {
                            Text("关闭电池优化（保活必需）")
                        }
                        Text(
                            if (batteryExempt) "✅ 电池优化已豁免（后台保活最佳）"
                            else "⚠ 电池优化未豁免：部分厂商会杀后台，建议点上方按钮关闭",
                            style = MaterialTheme.typography.bodySmall
                        )
                        OutlinedButton(onClick = onOpenNotifListener, modifier = Modifier.fillMaxWidth()) {
                            Text("开启「未接来电」通知监听权限")
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "开机 / 重启后本应用会自动按以上设置运行（自动开热点、监听转发、周期监测），无需再次点开。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(10.dp))
                        Divider()
                        Spacer(Modifier.height(8.dp))
                        Text("应用更新（当前 v$versionName）", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(6.dp))
                        OutlinedButton(onClick = onCheckUpdate, modifier = Modifier.fillMaxWidth()) {
                            Text("检查更新")
                        }
                        Text(updateStatus, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("功能开关", style = MaterialTheme.typography.titleMedium)
                        SwitchRow("充电自动开热点", settings.tetheringEnabled) {
                            onPatch { copy(tetheringEnabled = it) }
                            if (it) showHotspotGuide = true
                        }
                        OutlinedButton(
                            onClick = { showHotspotGuide = true },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("充电自动开热点 · 操作提示") }

                        SwitchRow("短信转发", settings.smsForwardEnabled) {
                            onPatch { copy(smsForwardEnabled = it) }
                        }
                        SwitchRow("未接来电转发", settings.missedCallForwardEnabled) {
                            onPatch { copy(missedCallForwardEnabled = it) }
                        }
                        Text(
                            "关闭「短信转发」会禁用短信接收组件（系统不再唤醒本应用）；关闭「未接来电转发」停止转发但保留系统通知监听授权，免去反复重新授权。两者均无需重启即可生效。",
                            style = MaterialTheme.typography.bodySmall
                        )
                        SwitchRow("低电量提醒", settings.lowBatteryEnabled) {
                            onPatch { copy(lowBatteryEnabled = it) }
                        }
                        SwitchRow("隐私：仅转发号码，隐藏短信正文", settings.hideBody) {
                            onPatch { copy(hideBody = it) }
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = settings.batteryThreshold.toString(),
                            onValueChange = {
                                it.toIntOrNull()?.let { v ->
                                    if (v in 1..100) onPatch { copy(batteryThreshold = v) }
                                }
                            },
                            label = { Text("低电量阈值 N%（默认 20）") },
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("夜间模式（安静时段）", style = MaterialTheme.typography.titleMedium)
                        SwitchRow("开启夜间模式", settings.nightModeEnabled) {
                            onPatch { copy(nightModeEnabled = it) }
                            if (!it) onReplayQueued()
                        }
                        if (settings.nightModeEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = settings.nightStartHour.toString(),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { v ->
                                            if (v in 0..23) onPatch { copy(nightStartHour = v) }
                                        }
                                    },
                                    label = { Text("开始小时(0-23)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = settings.nightEndHour.toString(),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { v ->
                                            if (v in 0..23) onPatch { copy(nightEndHour = v) }
                                        }
                                    },
                                    label = { Text("结束小时(0-23)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            val activeNow = NightModeManager.isActive(settings)
                            Text(
                                if (activeNow) "当前：夜间模式激活中，新消息将缓存待补发"
                                else "当前：非夜间时段（缓存消息会照常补发）",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "待补发缓存：${settings.queuedCount()} 条",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedButton(onClick = onReplayQueued, modifier = Modifier.fillMaxWidth()) {
                                Text("立即补发缓存消息")
                            }
                            Text(
                                "解除夜间模式后，缓存的短信 / 未接来电 / 低电量等消息会自动补发。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Shizuku 授权（自动开热点必需）", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            AssistChip(
                                onClick = onRequestShizuku,
                                label = { Text(if (shizukuReady) "已授权" else "请求授权") }
                            )
                        }
                        Text(
                            if (shizukuReady) "✅ Shizuku 已就绪，可自动开热点"
                            else "⚠ 未就绪：请先安装并运行 Shizuku，再点「请求授权」。否则热点只能手动开启。",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("推送渠道", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = {
                                onPatch {
                                    copy(channels = channels + ChannelConfig(
                                        id = UUID.randomUUID().toString(),
                                        type = ChannelType.BARK,
                                        name = "新渠道",
                                        enabled = true,
                                        url = "", token = "",
                                        template = ChannelConfig.DEFAULT_TEMPLATE
                                    ))
                                }
                            }) { Text("+ 添加") }
                        }
                        Spacer(Modifier.height(8.dp))
                        settings.channels.forEachIndexed { idx, ch ->
                            ChannelCard(
                                channel = ch,
                                onUpdate = { updated ->
                                    onPatch {
                                        copy(channels = channels.toMutableList().also { it[idx] = updated })
                                    }
                                },
                                onDelete = {
                                    onPatch { copy(channels = channels.filter { c -> c.id != ch.id }) }
                                }
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (settings.channels.isEmpty()) {
                            Text("尚未配置渠道。添加 Bark / Server 酱 / 企业微信 / 自定义 Webhook 后，短信、未接来电、低电量将推送到这里。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("转发审计日志", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = onRefreshAudit) { Text("刷新") }
                        }
                        Spacer(Modifier.height(6.dp))
                        if (auditLines.isEmpty()) {
                            Text("暂无记录", style = MaterialTheme.typography.bodySmall)
                        } else {
                            auditLines.reversed().forEach {
                                Text(it, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showHotspotGuide) {
        AlertDialog(
            onDismissRequest = { showHotspotGuide = false },
            confirmButton = {
                TextButton(onClick = { showHotspotGuide = false }) { Text("知道了") }
            },
            title = { Text(if (isXiaomi) "充电自动开热点 · 操作提示" else "充电自动开热点 · 操作提示（需 Shizuku）") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(if (isXiaomi) GUIDE_XIAOMI else GUIDE_OTHER)
                }
            }
        )
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onToggle: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onToggle)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChannelCard(
    channel: ChannelConfig,
    onUpdate: (ChannelConfig) -> Unit,
    onDelete: () -> Unit
) {
    val types = ChannelType.entries
    var expanded by remember { mutableStateOf(false) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ExposedDropdownMenuBox(
                    expanded = expanded,
                    onExpandedChange = { expanded = it },
                    modifier = Modifier.weight(1f)
                ) {
                    OutlinedTextField(
                        value = channel.type.label,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类型") },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
                    )
                    ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        types.forEach { t ->
                            DropdownMenuItem(text = { Text(t.label) }, onClick = {
                                onUpdate(channel.copy(type = t))
                                expanded = false
                            })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = onDelete) { Text("🗑") }
            }
            OutlinedTextField(
                value = channel.name,
                onValueChange = { onUpdate(channel.copy(name = it)) },
                label = { Text("名称") },
                modifier = Modifier.fillMaxWidth()
            )
            if (channel.type != ChannelType.WEBHOOK) {
                OutlinedTextField(
                    value = channel.token,
                    onValueChange = { onUpdate(channel.copy(token = it)) },
                    label = { Text("Key / Token") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            if (channel.type == ChannelType.WEBHOOK) {
                OutlinedTextField(
                    value = channel.url,
                    onValueChange = { onUpdate(channel.copy(url = it)) },
                    label = { Text("完整端点 URL") },
                    modifier = Modifier.fillMaxWidth()
                )
                Row {
                    OutlinedTextField(
                        value = channel.method,
                        onValueChange = { onUpdate(channel.copy(method = it.uppercase())) },
                        label = { Text("方法 GET/POST") },
                        modifier = Modifier.weight(1f)
                    )
                }
            } else if (channel.type == ChannelType.BARK) {
                OutlinedTextField(
                    value = channel.url,
                    onValueChange = { onUpdate(channel.copy(url = it)) },
                    label = { Text("服务器地址（留空=官方 api.day.app）") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
            OutlinedTextField(
                value = channel.template,
                onValueChange = { onUpdate(channel.copy(template = it)) },
                label = { Text("Body 模板（{{event}}/{{sender}}/{{body}}/{{time}}）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2
            )
            Row(Modifier.fillMaxWidth()) {
                Switch(channel.enabled, onCheckedChange = { onUpdate(channel.copy(enabled = it)) })
                Text("启用", modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
}
