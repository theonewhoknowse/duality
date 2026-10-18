
package com.thaz.duality

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipFile

private data class InstalledApp(
    val name: String,
    val packageName: String,
    val isSystemApp: Boolean,
    val apkPaths: List<String>
)

private data class ApkReport(
    val name: String,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val apkFiles: List<String>,
    val architectures: List<String>,
    val nativeLibraries: List<String>,
    val signingFingerprints: List<String>,
    val warnings: List<String>
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DualityApp()
                }
            }
        }
    }
}

@Composable
private fun DualityApp() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var installedApps by remember {
        mutableStateOf<List<InstalledApp>>(emptyList())
    }

    var selectedApp by remember {
        mutableStateOf<InstalledApp?>(null)
    }

    var report by remember {
        mutableStateOf<ApkReport?>(null)
    }

    var showSystemApps by remember {
        mutableStateOf(false)
    }

    var isLoading by remember {
        mutableStateOf(false)
    }

    var pendingOutputApk by remember {
        mutableStateOf<File?>(null)
    }

    var logs by remember {
        mutableStateOf(
            listOf(
                "Duality initialized.",
                "APK inspector ready.",
                "Workspace support enabled.",
                "Apktool decode/rebuild engine connected.",
                "APK signing engine connected.",
                "Frida Gadget injection is not implemented yet."
            )
        )
    }

    fun log(message: String) {
        logs = (logs + message).takeLast(200)
    }

    val exportPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            "application/vnd.android.package-archive"
        )
    ) { destinationUri: Uri? ->

        val source = pendingOutputApk
        pendingOutputApk = null

        if (destinationUri == null) {
            log("Export cancelled. APK remains in app storage.")
        } else if (source == null || !source.isFile) {
            log("EXPORT ERROR: The output APK could not be found.")
        } else {
            scope.launch {
                isLoading = true

                try {
                    withContext(Dispatchers.IO) {
                        val output = context.contentResolver
                            .openOutputStream(destinationUri)
                            ?: throw IllegalStateException(
                                "Could not open the destination file."
                            )

                        output.use { destination ->
                            source.inputStream().use { input ->
                                input.copyTo(destination)
                            }
                        }
                    }

                    log("EXPORT SUCCESS: ${source.name}")
                    log("Exported size: ${formatBytes(source.length())}")
                } catch (e: Exception) {
                    val details = generateSequence<Throwable>(e) { it.cause }
                        .take(10)
                        .joinToString(" | ") { error ->
                            "${error.javaClass.simpleName}: ${error.message}"
                        }

                    log("PATCH ERROR: $details")
                } finally {
                    isLoading = false
                }
            }
        }
    }

    fun refreshApps() {
        scope.launch {
            isLoading = true
            log("Scanning installed applications...")

            try {
                installedApps = withContext(Dispatchers.IO) {
                    loadInstalledApps(context)
                }

                log("Found ${installedApps.size} installed packages.")
            } catch (e: Exception) {
                log(
                    "ERROR: " +
                            (e.message ?: "Could not list installed apps.")
                )
            } finally {
                isLoading = false
            }
        }
    }

    val apkPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->

        if (uri == null) {
            log("APK selection cancelled.")
        } else {
            scope.launch {
                isLoading = true
                log("Inspecting selected APK...")

                try {
                    val result = withContext(Dispatchers.IO) {
                        inspectSelectedApk(context, uri)
                    }

                    report = result
                    selectedApp = null

                    log("Inspection complete: ${result.packageName}")
                    result.warnings.forEach {
                        log("WARNING: $it")
                    }
                } catch (e: Exception) {
                    log(
                        "INSPECTION ERROR: " +
                                (e.message ?: "Unknown error")
                    )
                } finally {
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshApps()
    }

    val visibleApps = installedApps.filter {
        showSystemApps || !it.isSystemApp
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            text = "DUALITY",
            fontSize = 30.sp,
            fontWeight = FontWeight.Black
        )

        Text(
            text = "Android APK Instrumentation Toolkit",
            style = MaterialTheme.typography.bodyMedium
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = { refreshApps() },
                enabled = !isLoading,
                modifier = Modifier.weight(1f)
            ) {
                Text("Refresh Apps")
            }

            OutlinedButton(
                onClick = {
                    apkPicker.launch(
                        arrayOf("application/vnd.android.package-archive")
                    )
                },
                enabled = !isLoading,
                modifier = Modifier.weight(1f)
            ) {
                Text("Choose APK")
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "Show system apps",
                modifier = Modifier.weight(1f)
            )

            Switch(
                checked = showSystemApps,
                onCheckedChange = { showSystemApps = it }
            )
        }

        Divider()

        Text(
            text = "INSTALLED APPS (${visibleApps.size})",
            fontWeight = FontWeight.Bold
        )

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            if (isLoading && installedApps.isEmpty()) {
                item {
                    Text("Loading applications...")
                }
            }

            if (!isLoading && visibleApps.isEmpty()) {
                item {
                    Text("No matching apps found. Try refreshing.")
                }
            }

            items(visibleApps, key = { it.packageName }) { app ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            selectedApp = app
                            report = null

                            log(
                                "Selected ${app.name} " +
                                        "(${app.packageName})."
                            )
                        }
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp)
                    ) {
                        Text(
                            app.name,
                            fontWeight = FontWeight.SemiBold
                        )

                        Text(
                            app.packageName,
                            style = MaterialTheme.typography.bodySmall
                        )

                        if (app.isSystemApp) {
                            Text(
                                "System app",
                                style = MaterialTheme.typography.labelSmall
                            )
                        }

                        Text(
                            "${app.apkPaths.size} accessible APK path(s)",
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        }

        selectedApp?.let { app ->
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "SELECTED APP",
                        fontWeight = FontWeight.Bold
                    )

                    Text(app.name)

                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall
                    )

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true
                                    log("Inspecting ${app.packageName}...")

                                    try {
                                        val result = withContext(
                                            Dispatchers.IO
                                        ) {
                                            inspectInstalledApp(context, app)
                                        }

                                        report = result
                                        log("Inspection complete.")

                                        result.warnings.forEach {
                                            log("WARNING: $it")
                                        }
                                    } catch (e: Exception) {
                                        log(
                                            "INSPECTION ERROR: " +
                                                    (e.message ?: "Unknown error")
                                        )
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = !isLoading,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Inspect")
                        }

                        Button(
                            onClick = {
                                scope.launch {
                                    isLoading = true

                                    log(
                                        "Preparing workspace for " +
                                                "${app.packageName}..."
                                    )

                                    try {
                                        val result = withContext(
                                            Dispatchers.IO
                                        ) {
                                            ApkWorkspace.prepare(
                                                context = context,
                                                packageName = app.packageName,
                                                sourcePaths = app.apkPaths
                                            )
                                        }

                                        log("WORKSPACE READY")
                                        log("Package: ${result.packageName}")
                                        log("APK files copied: ${result.apks.size}")
                                        log(
                                            "Total size: " +
                                                    formatBytes(result.totalBytes)
                                        )

                                        result.apks.forEach { apk ->
                                            log("File: ${apk.workspaceFile.name}")
                                            log(
                                                "Size: " +
                                                        formatBytes(apk.sizeBytes)
                                            )
                                            log("SHA-256: ${apk.sha256}")
                                        }
                                    } catch (e: Exception) {
                                        log(
                                            "WORKSPACE ERROR: " +
                                                    (e.message ?: "Unknown error")
                                        )
                                    } finally {
                                        isLoading = false
                                    }
                                }
                            },
                            enabled = !isLoading && app.apkPaths.isNotEmpty(),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Prepare")
                        }

                        OutlinedButton(
                            onClick = {
                                val sourcePaths = app.apkPaths

                                if (sourcePaths.size != 1) {
                                    log(
                                        "PATCH BLOCKED: Exactly one APK is " +
                                                "required. Found ${sourcePaths.size}. " +
                                                "Split APK support is not implemented."
                                    )
                                } else {
                                    val sourceApk = File(sourcePaths.single())

                                    scope.launch {
                                        isLoading = true

                                        try {
                                            log(
                                                "Starting patch pipeline for " +
                                                        "${app.packageName}..."
                                            )
                                            log("Source: ${sourceApk.name}")

                                            // Stage 1: Decode and rebuild.
                                            log(
                                                "STAGE 1/3: Decoding APK..."
                                            )

                                            val buildResult = withContext(
                                                Dispatchers.IO
                                            ) {
                                                ApktoolEngine.decodeAndRebuild(
                                                    context = context,
                                                    packageName = app.packageName,
                                                    sourceApk = sourceApk
                                                )
                                            }

                                            log("DECODE SUCCESS")
                                            log(
                                                "Decoded directory: " +
                                                        buildResult.decodedDirectory.name
                                            )
                                            log("REBUILD SUCCESS")
                                            log(
                                                "Unsigned APK: " +
                                                        buildResult.outputApk.name
                                            )
                                            log(
                                                "Unsigned size: " +
                                                        formatBytes(
                                                            buildResult.outputSizeBytes
                                                        )
                                            )

                                            // Stage 2: Sign and verify.
                                            log(
                                                "STAGE 2/3: Signing APK..."
                                            )

                                            val signingResult = withContext(
                                                Dispatchers.IO
                                            ) {
                                                ApkSigningEngine.sign(
                                                    context = context,
                                                    unsignedApk =
                                                        buildResult.outputApk
                                                )
                                            }

                                            log("SIGN SUCCESS")
                                            log(
                                                "Signed APK: " +
                                                        signingResult.outputApk.name
                                            )
                                            log(
                                                "Signed size: " +
                                                        formatBytes(
                                                            signingResult.outputSizeBytes
                                                        )
                                            )
                                            log(
                                                "Certificate SHA-256: " +
                                                        signingResult.certificateSha256
                                            )
                                            log(
                                                "APK signature verification passed."
                                            )

                                            // Stage 3: Export.
                                            log(
                                                "STAGE 3/3: Ready to export."
                                            )
                                            log(
                                                "NOTE: Frida Gadget injection " +
                                                        "is not implemented yet."
                                            )

                                            pendingOutputApk =
                                                signingResult.outputApk

                                            exportPicker.launch(
                                                signingResult.outputApk.name
                                            )
                                        } catch (e: Exception) {
                                            log(
                                                "PATCH ERROR: " +
                                                        (e.message
                                                            ?: e.javaClass.simpleName)
                                            )
                                        } finally {
                                            isLoading = false
                                        }
                                    }
                                }
                            },
                            enabled = !isLoading && app.apkPaths.size == 1,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Patch")
                        }
                    }
                }
            }
        }

        report?.let { result ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.8f, fill = false)
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(5.dp)
                ) {
                    Text(
                        "APK INSPECTION",
                        fontWeight = FontWeight.Bold
                    )

                    ReportLine("Name", result.name)
                    ReportLine("Package", result.packageName)
                    ReportLine("Version", result.versionName)
                    ReportLine("Version code", result.versionCode.toString())
                    ReportLine("Minimum SDK", result.minSdk.toString())
                    ReportLine("Target SDK", result.targetSdk.toString())
                    ReportLine("APK file count", result.apkFiles.size.toString())

                    ReportLine(
                        "Architectures",
                        result.architectures.joinToString()
                            .ifBlank { "None detected" }
                    )

                    Text(
                        "APK paths / files",
                        fontWeight = FontWeight.SemiBold
                    )

                    result.apkFiles.forEach {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    Text(
                        "Native libraries",
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        result.nativeLibraries.take(40).joinToString("\n")
                            .ifBlank { "No native libraries detected." },
                        style = MaterialTheme.typography.bodySmall
                    )

                    Text(
                        "Signing certificate SHA-256",
                        fontWeight = FontWeight.SemiBold
                    )

                    Text(
                        result.signingFingerprints.joinToString("\n")
                            .ifBlank {
                                "Certificate fingerprint unavailable."
                            },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace
                    )

                    result.warnings.forEach {
                        Text(
                            "WARNING: $it",
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
        }

        Divider()

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("TERMINAL", fontWeight = FontWeight.Bold)

            Spacer(modifier = Modifier.weight(1f))

            TextButton(
                onClick = { logs = emptyList() }
            ) {
                Text("Clear")
            }
        }

        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(10.dp)
            ) {
                logs.forEach { line ->
                    Text(
                        text = "> $line",
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ReportLine(
    label: String,
    value: String
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        Text(
            label,
            fontWeight = FontWeight.SemiBold
        )

        Text(
            value,
            style = MaterialTheme.typography.bodySmall
        )
    }
}

private fun loadInstalledApps(
    context: Context
): List<InstalledApp> {
    val pm = context.packageManager

    return getInstalledApplicationsCompat(pm)
        .filter { it.packageName != context.packageName }
        .mapNotNull { appInfo ->
            try {
                val paths = buildList {
                    appInfo.sourceDir?.let { add(it) }
                    appInfo.splitSourceDirs?.let { addAll(it) }
                }.distinct()

                InstalledApp(
                    name = pm.getApplicationLabel(appInfo).toString(),
                    packageName = appInfo.packageName,
                    isSystemApp =
                        appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
                    apkPaths = paths
                )
            } catch (_: Exception) {
                null
            }
        }
        .sortedBy { it.name.lowercase(Locale.ROOT) }
}

@Suppress("DEPRECATION")
private fun getInstalledApplicationsCompat(
    pm: PackageManager
): List<ApplicationInfo> {
    return if (Build.VERSION.SDK_INT >= 33) {
        pm.getInstalledApplications(
            PackageManager.ApplicationInfoFlags.of(0L)
        )
    } else {
        pm.getInstalledApplications(0)
    }
}

@Suppress("DEPRECATION")
private fun getPackageInfoCompat(
    pm: PackageManager,
    packageName: String
): PackageInfo {
    val flags = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    return if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageInfo(
            packageName,
            PackageManager.PackageInfoFlags.of(flags.toLong())
        )
    } else {
        pm.getPackageInfo(packageName, flags)
    }
}

@Suppress("DEPRECATION")
private fun getArchiveInfoCompat(
    pm: PackageManager,
    path: String
): PackageInfo? {
    val flags = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    return if (Build.VERSION.SDK_INT >= 33) {
        pm.getPackageArchiveInfo(
            path,
            PackageManager.PackageInfoFlags.of(flags.toLong())
        )
    } else {
        pm.getPackageArchiveInfo(path, flags)
    }
}

private fun inspectInstalledApp(
    context: Context,
    app: InstalledApp
): ApkReport {
    val pm = context.packageManager
    val info = getPackageInfoCompat(pm, app.packageName)

    val appInfo = info.applicationInfo
        ?: throw IllegalStateException(
            "Application metadata is unavailable."
        )

    val architectures = mutableSetOf<String>()
    val libraries = mutableSetOf<String>()
    val warnings = mutableListOf<String>()

    app.apkPaths.forEach { path ->
        try {
            scanNativeLibraries(
                path,
                architectures,
                libraries
            )
        } catch (e: Exception) {
            warnings.add("Could not read $path: ${e.message}")
        }
    }

    if (app.apkPaths.size > 1) {
        warnings.add(
            "This app has split APKs. A future patch must preserve all required splits."
        )
    }

    if (app.apkPaths.isEmpty()) {
        warnings.add(
            "No APK paths are accessible through application metadata."
        )
    }

    return ApkReport(
        name = pm.getApplicationLabel(appInfo).toString(),
        packageName = app.packageName,
        versionName = info.versionName ?: "Unknown",
        versionCode = if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        },
        minSdk = appInfo.minSdkVersion,
        targetSdk = appInfo.targetSdkVersion,
        apkFiles = app.apkPaths,
        architectures = architectures.sorted(),
        nativeLibraries = libraries.sorted(),
        signingFingerprints = getSigningFingerprints(info),
        warnings = warnings
    )
}

private fun inspectSelectedApk(
    context: Context,
    uri: Uri
): ApkReport {
    val tempFile = File.createTempFile(
        "duality_inspect_",
        ".apk",
        context.cacheDir
    )

    try {
        val input = context.contentResolver.openInputStream(uri)
            ?: throw IllegalStateException(
                "Could not open the selected file."
            )

        input.use { source ->
            tempFile.outputStream().use { destination ->
                source.copyTo(destination)
            }
        }

        val pm = context.packageManager

        val info = getArchiveInfoCompat(
            pm,
            tempFile.absolutePath
        ) ?: throw IllegalArgumentException(
            "The selected file is not a readable APK."
        )

        val appInfo = info.applicationInfo
            ?: throw IllegalArgumentException(
                "APK application metadata is missing."
            )

        val architectures = mutableSetOf<String>()
        val libraries = mutableSetOf<String>()
        val warnings = mutableListOf<String>()

        scanNativeLibraries(
            tempFile.absolutePath,
            architectures,
            libraries
        )

        if (architectures.isEmpty()) {
            warnings.add("No native ABI directories were detected.")
        }

        return ApkReport(
            name = try {
                pm.getApplicationLabel(appInfo).toString()
            } catch (_: Exception) {
                displayName(context, uri)
            },
            packageName = info.packageName ?: "Unknown",
            versionName = info.versionName ?: "Unknown",
            versionCode = if (Build.VERSION.SDK_INT >= 28) {
                info.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                info.versionCode.toLong()
            },
            minSdk = appInfo.minSdkVersion,
            targetSdk = appInfo.targetSdkVersion,
            apkFiles = listOf(
                "${displayName(context, uri)} " +
                        "(${formatBytes(tempFile.length())})"
            ),
            architectures = architectures.sorted(),
            nativeLibraries = libraries.sorted(),
            signingFingerprints = getSigningFingerprints(info),
            warnings = warnings
        )
    } finally {
        tempFile.delete()
    }
}

private fun scanNativeLibraries(
    apkPath: String,
    architectures: MutableSet<String>,
    libraries: MutableSet<String>
) {
    ZipFile(apkPath).use { zip ->
        val entries = zip.entries()

        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (entry.isDirectory) continue

            val parts = entry.name.split('/')

            if (
                parts.size >= 3 &&
                parts[0] == "lib" &&
                parts.last().endsWith(".so")
            ) {
                architectures.add(parts[1])
                libraries.add(entry.name)
            }
        }
    }
}

@Suppress("DEPRECATION")
private fun getSigningFingerprints(
    info: PackageInfo
): List<String> {
    val signatures = if (Build.VERSION.SDK_INT >= 28) {
        info.signingInfo?.apkContentsSigners?.toList().orEmpty()
    } else {
        info.signatures?.toList().orEmpty()
    }

    return signatures.map { signature ->
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(signature.toByteArray())

        digest.joinToString(":") {
            "%02X".format(Locale.ROOT, it.toInt() and 0xFF)
        }
    }
}

private fun displayName(
    context: Context,
    uri: Uri
): String {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                cursor.getString(0) ?: "Selected APK"
            } else {
                "Selected APK"
            }
        } ?: "Selected APK"
    } catch (_: Exception) {
        "Selected APK"
    }
}

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"

    if (bytes < 1024 * 1024) {
        return String.format(
            Locale.ROOT,
            "%.1f KB",
            bytes / 1024.0
        )
    }

    return String.format(
        Locale.ROOT,
        "%.1f MB",
        bytes / (1024.0 * 1024.0)
    )
}
