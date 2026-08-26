package com.carwifi.app.ui

import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.documentfile.provider.DocumentFile
import com.carwifi.app.data.AppSettings
import com.carwifi.app.model.ChannelConfig
import com.carwifi.app.model.ChannelType
import com.carwifi.app.model.PresetInterface
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
    "开启后本应用仍会按设置转发短信 / 未接来电 / 低电量，并随热点自动共享文件。"

private const val GUIDE_OTHER = "本应用已移除热点自动开启的系统接口调用（普通应用无 Root / Shizuku 无法稳定控制热点）。请在系统设置手动开启热点：\n\n" +
    "设置 → 连接 → 个人热点 / 便携式 WLAN 热点 → 打开「便携式 WLAN 热点」。\n\n" +
    "部分机型可在「自动任务 / 情景智能 / 定时任务」中设置「充电时自动开热点」以实现免手动。"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: AppSettings,
    isXiaomi: Boolean,
    onPatch: (AppSettings.() -> AppSettings) -> Unit,
    onOpenNotifListener: () -> Unit,
    onOpenBatteryOpt: () -> Unit,
    batteryExempt: Boolean,
    onStartService: () -> Unit,
    onReplayQueued: () -> Unit,
    versionName: String,
    updateStatus: String,
    onCheckUpdate: () -> Unit,
    auditLines: List<String>,
    onRefreshAudit: () -> Unit,
    fileShareUrl: String,
    fileSharePath: String,
    catalog: List<PresetInterface>,
    onRefreshCatalog: () -> Unit,
    extraShareUris: List<String>,
    onPickFolder: () -> Unit,
    onRemoveFolder: (String) -> Unit,
    onTestChannel: (ChannelConfig) -> Unit
) {
    var showHotspotGuide by remember { mutableStateOf(false) }
    var showAuditDialog by remember { mutableStateOf(false) }

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
                        Text("车内文件共享（HTTP）", style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(8.dp))
                        SwitchRow("开热点时自动共享手机文件", settings.fileShareEnabled) {
                            onPatch { copy(fileShareEnabled = it) }
                        }
                        if (settings.fileShareEnabled) {
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = settings.fileSharePort.toString(),
                                    onValueChange = {
                                        it.toIntOrNull()?.let { v ->
                                            if (v in 1024..65535) onPatch { copy(fileSharePort = v) }
                                        }
                                    },
                                    label = { Text("端口(1024-65535)") },
                                    keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedTextField(
                                    value = settings.fileSharePassword,
                                    onValueChange = { onPatch { copy(fileSharePassword = it) } },
                                    label = { Text("访问密码(可选)") },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            if (fileShareUrl.isNotEmpty()) {
                                Text(
                                    "车机访问地址：$fileShareUrl",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "在车机浏览器打开上述地址即可浏览 / 下载 / 上传文件。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                if (settings.dlnaEnabled) {
                                    Text(
                                        "车机原生媒体 App（如系统「媒体」、VLC、Kodi）会自动发现「CarWifi Media」，可直接播放共享的 MP3 / 视频。",
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                Text(
                                    "开启热点并启用本功能后，此处会显示车机可访问的地址。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "共享根目录：$fileSharePath",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "「app」为 App 私有目录（可直接上传文件）；系统目录（如 Music）为只读，便于直接播共享的音乐 / 视频。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(10.dp))
                            Divider()
                            Spacer(Modifier.height(8.dp))
                            Text("共享系统文件夹（如 Music / Download）", style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(6.dp))
                            OutlinedButton(onClick = onPickFolder, modifier = Modifier.fillMaxWidth()) {
                                Text("+ 添加共享文件夹")
                            }
                            if (extraShareUris.isNotEmpty()) {
                                Spacer(Modifier.height(6.dp))
                                extraShareUris.forEach { uri ->
                                    val name = runCatching {
                                        DocumentFile.fromTreeUri(LocalContext.current, Uri.parse(uri))?.name
                                    }.getOrNull() ?: uri
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text("📁 $name", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                                        IconButton(onClick = { onRemoveFolder(uri) }) {
                                            Text("🗑", style = MaterialTheme.typography.bodyLarge)
                                        }
                                    }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            SwitchRow("DLNA 媒体服务器（车机原生媒体 App 自动发现）", settings.dlnaEnabled) {
                                onPatch { copy(dlnaEnabled = it) }
                            }
                            if (settings.dlnaEnabled) {
                                Text(
                                    "开启后，车机上的原生「媒体 / 网络」入口会直接发现名为「CarWifi Media」的媒体库，可流式播放 MP3 / 视频并拖动进度；同时保留浏览器访问。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            } else {
                                Text(
                                    "关闭 DLNA 后仅保留 HTTP 文件共享（车机需用浏览器或 VLC/MX Player 输入地址访问）。",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            Text(
                                "开启后，连上本机热点的车机可用浏览器访问手机文件；热点关闭时自动停止，平时不占用资源。",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
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
                            Text("推送渠道", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.weight(1f))
                            Button(onClick = {
                                onPatch {
                                    copy(channels = channels + ChannelConfig(
                                        id = UUID.randomUUID().toString(),
                                        type = ChannelType.BARK,
                                        name = "新渠道",
                                        enabled = true,
                                        interfaces = emptyList(),
                                        defaultTemplate = ChannelConfig.DEFAULT_TEMPLATE,
                                        defaultTitleTemplate = ChannelConfig.DEFAULT_TITLE
                                    ))
                                }
                            }) { Text("+ 添加") }
                        }
                        Spacer(Modifier.height(8.dp))
                        settings.channels.forEachIndexed { idx, ch ->
                            ChannelCard(
                                channel = ch,
                                catalog = catalog,
                                onRefreshCatalog = onRefreshCatalog,
                                onUpdate = { updated ->
                                    onPatch {
                                        copy(channels = channels.toMutableList().also { it[idx] = updated })
                                    }
                                },
                                onDelete = {
                                    onPatch { copy(channels = channels.filter { c -> c.id != ch.id }) }
                                },
                                onTestChannel = onTestChannel
                            )
                            Spacer(Modifier.height(10.dp))
                        }
                        if (settings.channels.isEmpty()) {
                            Text("尚未配置渠道。添加 Bark / WxPusher / PushPlus / 企业微信 / 自定义 Webhook 后，短信、未接来电、低电量将推送到这里。",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }

            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("转发审计日志", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    if (auditLines.isEmpty()) "最近暂无记录"
                                    else "最近 ${auditLines.size} 条记录",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            TextButton(onClick = { showAuditDialog = true }) { Text("查看") }
                        }
                    }
                }
            }
        }
    }

    if (showAuditDialog) {
        AlertDialog(
            onDismissRequest = { showAuditDialog = false },
            confirmButton = {
                TextButton(onClick = onRefreshAudit) { Text("刷新") }
            },
            dismissButton = {
                TextButton(onClick = { showAuditDialog = false }) { Text("关闭") }
            },
            title = { Text("转发审计日志") },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 360.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (auditLines.isEmpty()) {
                        Text("暂无记录", style = MaterialTheme.typography.bodySmall)
                    } else {
                        auditLines.reversed().forEach {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
            }
        )
    }

    if (showHotspotGuide) {
        AlertDialog(
            onDismissRequest = { showHotspotGuide = false },
            confirmButton = {
                TextButton(onClick = { showHotspotGuide = false }) { Text("知道了") }
            },
            title = { Text("充电自动开热点 · 操作提示") },
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
    catalog: List<PresetInterface>,
    onRefreshCatalog: () -> Unit,
    onUpdate: (ChannelConfig) -> Unit,
    onDelete: () -> Unit,
    onTestChannel: (ChannelConfig) -> Unit
) {
    val types = ChannelType.entries
    var expanded by remember { mutableStateOf(false) }
    val hasServer = channel.type in setOf(
        ChannelType.BARK, ChannelType.WEBHOOK, ChannelType.WXPUSHER, ChannelType.PUSHPLUS
    )

    fun updateIface(iface: PresetInterface, transform: PresetInterface.() -> PresetInterface) {
        onUpdate(channel.copy(interfaces = channel.interfaces.map {
            if (it.id == iface.id) it.transform() else it
        }))
    }

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
                                // 切换类型时清空已选接口，避免类型错配
                                onUpdate(channel.copy(type = t, interfaces = emptyList()))
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

            Spacer(Modifier.height(8.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("接口配置（来自 GitHub）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onRefreshCatalog) { Text("刷新") }
            }
            val matching = catalog.filter { it.type == channel.type }
            if (matching.isEmpty()) {
                Text(
                    "点「刷新」从 GitHub 拉取接口配置（仓库根 channels-catalog.json）。也可下方「手动添加接口」。",
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                matching.forEach { cat ->
                    val checked = channel.interfaces.any { it.id == cat.id }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = checked,
                            onCheckedChange = { on ->
                                onUpdate(
                                    if (on) channel.copy(interfaces = channel.interfaces + cat.copy(id = cat.id))
                                    else channel.copy(interfaces = channel.interfaces.filter { it.id != cat.id })
                                )
                            }
                        )
                        Column(Modifier.weight(1f)) {
                            Text(cat.name.ifBlank { cat.type.label }, style = MaterialTheme.typography.bodyMedium)
                            if (cat.server.isNotBlank()) {
                                Text(cat.server, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = {
                    onUpdate(
                        channel.copy(
                            interfaces = channel.interfaces + PresetInterface(
                                id = UUID.randomUUID().toString(),
                                type = channel.type
                            )
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth()
            ) { Text("+ 手动添加接口") }

            channel.interfaces.forEach { iface ->
                Spacer(Modifier.height(8.dp))
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                iface.name.ifBlank { "接口" },
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = {
                                onUpdate(channel.copy(interfaces = channel.interfaces.filter { it.id != iface.id }))
                            }) { Text("🗑") }
                        }
                        if (channel.type != ChannelType.WEBHOOK) {
                            OutlinedTextField(
                                value = iface.token,
                                onValueChange = { updateIface(iface) { copy(token = it) } },
                                label = { Text("Key / Token") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (hasServer) {
                            OutlinedTextField(
                                value = iface.server,
                                onValueChange = { updateIface(iface) { copy(server = it) } },
                                label = { Text("服务器地址") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (channel.type == ChannelType.WEBHOOK) {
                            OutlinedTextField(
                                value = iface.method,
                                onValueChange = { updateIface(iface) { copy(method = it.uppercase()) } },
                                label = { Text("方法 GET/POST") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (channel.type == ChannelType.WXPUSHER) {
                            OutlinedTextField(
                                value = iface.extra,
                                onValueChange = { updateIface(iface) { copy(extra = it) } },
                                label = { Text("接收目标 UID / Topic ID（必填）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "填 UID（如 UID_xxx）单发；或填数字 Topic ID 群发；多个逗号分隔。也可写 uids=...,topicIds=... 精确指定。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        if (channel.type == ChannelType.PUSHPLUS) {
                            OutlinedTextField(
                                value = iface.extra,
                                onValueChange = { updateIface(iface) { copy(extra = it) } },
                                label = { Text("群组编码 topic（可选）") },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "留空仅发送给自己；填群组编码可一对多发送给群组成员。",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(Modifier.height(6.dp))
                        }
                        OutlinedTextField(
                            value = iface.template,
                            onValueChange = { updateIface(iface) { copy(template = it) } },
                            label = { Text("Body 模板（{{event}}/{{sender}}/{{body}}/{{time}}）") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))
            Divider()
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = channel.defaultTemplate,
                onValueChange = { onUpdate(channel.copy(defaultTemplate = it)) },
                label = { Text("默认 Body 模板（兜底，接口未指定时使用）") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 5
            )
            Spacer(Modifier.height(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Switch(channel.enabled, onCheckedChange = { onUpdate(channel.copy(enabled = it)) })
                Text("启用", modifier = Modifier.align(Alignment.CenterVertically))
                Spacer(Modifier.weight(1f))
                Button(onClick = { onTestChannel(channel) }) { Text("测试") }
            }
        }
    }
}
