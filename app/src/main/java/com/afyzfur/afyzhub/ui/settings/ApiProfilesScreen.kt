package com.afyzfur.afyzhub.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.afyzfur.afyzhub.domain.model.ApiProfile
import org.koin.androidx.compose.koinViewModel

/**
 * API 配置组列表。
 *
 * 一组配置 = 一份 Key + 地址 + 模型，可自定义名称并归入分组。
 * 同一家有多个 Key（不同额度、不同中转）时不必再来回覆盖。
 *
 * 点一行即把这组设为生效，点行尾铅笔进入编辑。切换是高频操作
 * 给整行，编辑是低频操作收进按钮。
 */
@Composable
fun ApiProfilesScreen(
    onNavigateBack: () -> Unit,
    onEditProfile: (String) -> Unit,
    viewModel: ApiProfilesViewModel = koinViewModel()
) {
    val store by viewModel.store.collectAsState()
    var pendingDelete by remember { mutableStateOf<ApiProfile?>(null) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = Modifier.fillMaxSize()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            SettingsPageHeader(title = "API 配置", onNavigateBack = onNavigateBack)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
            ) {
                if (store.profiles.isEmpty()) {
                    Spacer(Modifier.height(24.dp))
                    Text(
                        text = "还没有配置。新建一组后填入 API Key 即可开始对话。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 28.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // 按分组分块展示，未分组的排在最后
                store.grouped().forEach { (group, profiles) ->
                    SettingsCategoryTitle(group.ifBlank { "未分组" })
                    SettingsGroup {
                        profiles.forEachIndexed { index, profile ->
                            if (index > 0) SettingsItemDivider()
                            ProfileRow(
                                profile = profile,
                                selected = profile.id == store.active?.id,
                                onSelect = { viewModel.selectProfile(profile.id) },
                                onEdit = { onEditProfile(profile.id) }
                            )
                        }
                    }
                }

                SettingsCategoryTitle("管理")
                SettingsGroup {
                    SettingsActionItem(
                        icon = Icons.Default.Add,
                        title = "新建配置",
                        subtitle = "建好后直接进入编辑",
                        // 建完就跳进去填 Key，省一次点击
                        onClick = { viewModel.addProfile(onCreated = onEditProfile) }
                    )
                    store.active?.let { active ->
                        SettingsItemDivider()
                        SettingsActionItem(
                            icon = Icons.Default.Add,
                            title = "复制当前配置",
                            subtitle = "基于「${active.displayName}」建一组副本",
                            onClick = { viewModel.duplicateProfile(active.id) }
                        )
                        SettingsItemDivider()
                        SettingsActionItem(
                            icon = Icons.Default.Delete,
                            title = "删除当前配置",
                            subtitle = active.displayName,
                            destructive = true,
                            onClick = { pendingDelete = active }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text(
                    text = "选中的配置对全部会话生效。API Key 仅存在本机，不会上传到第三方。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp)
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    // 删除不可撤销，Key 丢了要重新去服务商那边取，值得确认一次
    pendingDelete?.let { target ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除配置") },
            text = { Text("将删除「${target.displayName}」，其中的 API Key 也会一并清除。") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteProfile(target.id)
                    pendingDelete = null
                }) {
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

/**
 * 配置组的一行。
 *
 * 整行点击把这组设为生效——切换是这里最高频的操作；编辑收进
 * 行尾的铅笔按钮。生效状态只用一个单选圆点表达，不再叠加
 * 背景高亮、多重对勾这些视觉噪音。
 */
@Composable
private fun ProfileRow(
    profile: ApiProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
    ) {
        RadioButton(
            selected = selected,
            // 点击由整行承接：按钮自身不再响应，避免一行出现两个涟漪
            onClick = null
        )
        Spacer(Modifier.size(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = profile.displayName,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                // Key 是否填过比 Key 本身更有用，列表里不该露出明文
                text = buildString {
                    append(profile.effectiveModel)
                    if (profile.apiKey.isBlank()) append("　未填 Key")
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (profile.apiKey.isBlank()) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 编辑是低频操作，收成图标按钮，不占整行的点击区
        IconButton(onClick = onEdit, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = Icons.Default.Edit,
                contentDescription = "编辑",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}
