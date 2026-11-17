package com.example.ui.screens

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.BuildConfig
import com.example.ui.components.GlassPanel
import com.example.ui.components.SectionTitle
import com.example.ui.theme.MeshAmber
import com.example.ui.theme.MeshEmerald
import com.example.ui.theme.NeonCyan
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private data class ReleaseInfo(
    val tag: String,
    val title: String,
    val notes: String,
    val pageUrl: String,
    val apkUrl: String?
)

@Composable
fun SupportCenterPanel() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var category by remember { mutableStateOf("Crash") }
    var severity by remember { mutableStateOf("Normal") }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var includeDeviceInfo by remember { mutableStateOf(true) }
    var reportStatus by remember { mutableStateOf("Reports are saved on this device before opening GitHub.") }
    var release by remember { mutableStateOf<ReleaseInfo?>(null) }
    var updateStatus by remember { mutableStateOf("Check GitHub Releases for the latest stable version.") }
    var checking by remember { mutableStateOf(false) }
    var downloadId by remember { mutableStateOf<Long?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        GlassPanel(accentColor = MeshAmber) {
            SectionTitle(
                title = "Built-in Bug Reports",
                subtitle = "Create a useful report without sharing private chats, files or cryptographic keys"
            )
            Spacer(Modifier.height(8.dp))
            Text("Issue type", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Crash", "Bug", "Network", "File transfer", "UI").forEach {
                    FilterChip(selected = category == it, onClick = { category = it }, label = { Text(it) })
                }
            }
            Text("Severity", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("Low", "Normal", "High", "Blocker").forEach {
                    FilterChip(selected = severity == it, onClick = { severity = it }, label = { Text(it) })
                }
            }
            OutlinedTextField(
                value = title,
                onValueChange = { title = it.take(120) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Short summary") },
                supportingText = { Text("${title.length}/120") },
                singleLine = true
            )
            OutlinedTextField(
                value = description,
                onValueChange = { description = it.take(5000) },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("What happened? Steps to reproduce and expected result") },
                supportingText = { Text("${description.length}/5000") },
                minLines = 4,
                maxLines = 7
            )
            androidx.compose.material3.CheckboxRow(
                checked = includeDeviceInfo,
                onCheckedChange = { includeDeviceInfo = it },
                label = "Include basic device and app version details"
            )
            Button(
                enabled = title.isNotBlank() && description.isNotBlank(),
                onClick = {
                    val device = if (includeDeviceInfo) {
                        "\n\n--- Environment ---\nApp: MeshLink ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\nAndroid: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})\nDevice: ${Build.MANUFACTURER} ${Build.MODEL}"
                    } else ""
                    val body = "## Bug report\n**Type:** $category\n**Severity:** $severity\n\n## Description\n${description.trim()}$device\n\n## Privacy\nThis report was reviewed by the user before submission."
                    val safeTitle = "[${category.uppercase()}] ${title.trim()}"
                    scope.launch {
                        val saved = withContext(Dispatchers.IO) {
                            runCatching {
                                val dir = File(context.filesDir, "bug-reports").apply { mkdirs() }
                                val file = File(dir, "report-${System.currentTimeMillis()}.txt")
                                file.writeText("$safeTitle\n\n$body")
                                file
                            }.getOrNull()
                        }
                        reportStatus = if (saved != null) "Saved locally: ${saved.name}. Review and submit the prefilled GitHub issue in your browser." else "Could not save report locally."
                        if (saved != null) {
                            val issueUrl = Uri.parse("https://github.com/abdulraheemnohri/MeshLink/issues/new")
                                .buildUpon()
                                .appendQueryParameter("title", safeTitle)
                                .appendQueryParameter("body", body)
                                .appendQueryParameter("labels", "bug")
                                .build()
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, issueUrl)) }
                                .onFailure { reportStatus = "Report saved locally, but no browser could be opened." }
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Icon(Icons.Filled.BugReport, contentDescription = null)
                Text("Save report & open GitHub")
            }
            Text(reportStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "MeshLink does not upload reports in the background. The GitHub issue form opens for your review and requires you to sign in and press Submit. Do not include secrets or private message content.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        GlassPanel(accentColor = NeonCyan) {
            SectionTitle(
                title = "App Update Center",
                subtitle = "Stable releases from the official MeshLink GitHub repository"
            )
            Text("Installed: ${BuildConfig.VERSION_NAME} (code ${BuildConfig.VERSION_CODE})", style = MaterialTheme.typography.bodyMedium)
            Button(
                enabled = !checking,
                onClick = {
                    checking = true
                    updateStatus = "Checking latest stable release…"
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { fetchLatestRelease() }
                        release = result
                        checking = false
                        updateStatus = if (result == null) {
                            "Could not check releases. Check your internet connection and try again."
                        } else if (isNewerVersion(result.tag, BuildConfig.VERSION_NAME)) {
                            "Update available: ${result.tag}"
                        } else {
                            "You are using the latest release (${result.tag})."
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                androidx.compose.material3.Icon(Icons.Filled.SystemUpdate, contentDescription = null)
                Text(if (checking) "Checking…" else "Check for updates")
            }
            release?.let { latest ->
                Text(latest.title.ifBlank { latest.tag }, style = MaterialTheme.typography.titleMedium)
                Text("Latest stable: ${latest.tag}", style = MaterialTheme.typography.labelLarge, color = MeshEmerald)
                if (latest.notes.isNotBlank()) {
                    Text(latest.notes.take(1800), style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.pageUrl))) }
                    }) { Text("Release page") }
                    if (isNewerVersion(latest.tag, BuildConfig.VERSION_NAME)) {
                        Button(onClick = {
                            val apk = latest.apkUrl
                            if (apk == null) {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(latest.pageUrl))) }
                                updateStatus = "No APK asset was attached. Open the release page to download the correct build."
                            } else {
                                runCatching {
                                    val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                                    val request = DownloadManager.Request(Uri.parse(apk))
                                        .setTitle("MeshLink update ${latest.tag}")
                                        .setDescription("Downloading official MeshLink release APK")
                                        .setMimeType("application/vnd.android.package-archive")
                                        .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                                        .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, "MeshLink-update.apk")
                                    downloadId = manager.enqueue(request)
                                    updateStatus = "Download started. When complete, use Install downloaded update below."
                                }.onFailure { updateStatus = "Download could not start: ${it.message}" }
                            }
                        }) { Text("Download APK") }
                    }
                }
            }
            downloadId?.let { id ->
                Button(
                    onClick = {
                        val manager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                        val cursor = manager.query(DownloadManager.Query().setFilterById(id))
                        val status = cursor?.use {
                            if (it.moveToFirst()) it.getInt(it.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) else -1
                        } ?: -1
                        if (status == DownloadManager.STATUS_SUCCESSFUL) {
                            val apkUri = manager.getUriForDownloadedFile(id)
                            if (apkUri != null) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
                                    context.startActivity(Intent(AndroidSettings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
                                    updateStatus = "Allow installs from MeshLink in Android settings, then tap Install downloaded update again."
                                } else {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW).apply {
                                            setDataAndType(apkUri, "application/vnd.android.package-archive")
                                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                        })
                                    }.onFailure { updateStatus = "Installer could not be opened: ${it.message}" }
                                }
                            }
                        } else if (status == DownloadManager.STATUS_FAILED) {
                            updateStatus = "Download failed. Open the release page and try again."
                        } else {
                            updateStatus = "Download is not complete yet. Check the Android download notification."
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Install downloaded update") }
            }
            Text(updateStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Updates are optional and user-confirmed. Android will verify the APK signature; the update must be signed with the same signing key as the installed app. Never install an APK from an unofficial source.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun androidx.compose.material3.CheckboxRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
        androidx.compose.material3.Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, modifier = Modifier.padding(top = 12.dp), style = MaterialTheme.typography.bodySmall)
    }
}

private fun fetchLatestRelease(): ReleaseInfo? {
    return runCatching {
        val connection = (URL("https://api.github.com/repos/abdulraheemnohri/MeshLink/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = 8000
            readTimeout = 8000
            requestMethod = "GET"
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            setRequestProperty("User-Agent", "MeshLink-Android")
        }
        try {
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val assets = json.optJSONArray("assets")
            var apk: String? = null
            if (assets != null) {
                for (index in 0 until assets.length()) {
                    val asset = assets.optJSONObject(index) ?: continue
                    val name = asset.optString("name")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apk = asset.optString("browser_download_url").takeIf { it.startsWith("https://") }
                        break
                    }
                }
            }
            ReleaseInfo(
                tag = json.optString("tag_name"),
                title = json.optString("name"),
                notes = json.optString("body"),
                pageUrl = json.optString("html_url"),
                apkUrl = apk
            ).takeIf { it.tag.isNotBlank() && it.pageUrl.startsWith("https://") }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()
}

private fun isNewerVersion(latest: String, current: String): Boolean {
    fun parts(value: String): List<Int> = value.trim().removePrefix("v").substringBefore("-")
        .split(".").map { it.toIntOrNull() ?: 0 }
    val a = parts(latest)
    val b = parts(current)
    for (index in 0 until maxOf(a.size, b.size)) {
        val left = a.getOrElse(index) { 0 }
        val right = b.getOrElse(index) { 0 }
        if (left != right) return left > right
    }
    return false
}
