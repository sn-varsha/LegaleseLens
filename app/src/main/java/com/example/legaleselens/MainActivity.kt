package com.example.legaleselens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.google.ai.client.generativeai.GenerativeModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val geminiApiKey = "API KEY"
    
    enum class AppScreen {
        SPLASH, DASHBOARD, CAMERA_SCAN, HISTORY, HISTORY_DETAIL, DICTIONARY, TEMPLATES, ABOUT
    }

    enum class ScanMode {
        SINGLE, COMPARE
    }

    enum class ScanState {
        IDLE,
        SCANNING_FIRST,
        FIRST_SCANNED,
        SCANNING_SECOND,
        COMPARING,
        RESULTS
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requestPermissionLauncher = registerForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { isGranted: Boolean ->
            if (!isGranted) {
                Toast.makeText(this, "Camera permission required.", Toast.LENGTH_SHORT).show()
            }
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }

        setContent {
            LegaleseLensTheme {
                LegaleseLensApp(geminiApiKey)
            }
        }
    }
}

fun parseMarkdownToAnnotatedString(text: String, primaryColor: Color, errorColor: Color): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.split("\n")
        for (line in lines) {
            when {
                line.startsWith("### ") -> {
                    withStyle(style = SpanStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = primaryColor)) {
                        append(line.removePrefix("### ") + "\n")
                    }
                }
                line.startsWith("## ") -> {
                    withStyle(style = SpanStyle(fontSize = 22.sp, fontWeight = FontWeight.Bold, color = primaryColor)) {
                        append(line.removePrefix("## ") + "\n")
                    }
                }
                line.startsWith("# ") -> {
                    withStyle(style = SpanStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = primaryColor)) {
                        append(line.removePrefix("# ") + "\n")
                    }
                }
                line.startsWith("- ") || line.startsWith("* ") -> {
                    append("• ")
                    val content = line.substring(2)
                    parseInlineMarkdown(content, errorColor)
                    append("\n")
                }
                else -> {
                    parseInlineMarkdown(line, errorColor)
                    append("\n")
                }
            }
        }
    }
}

fun AnnotatedString.Builder.parseInlineMarkdown(text: String, errorColor: Color) {
    var currentIndex = 0
    val boldRegex = Regex("\\*\\*(.*?)\\*\\*")
    val matches = boldRegex.findAll(text)

    for (match in matches) {
        if (match.range.first > currentIndex) {
            append(text.substring(currentIndex, match.range.first))
        }
        val isWarning = match.value.contains("RISK", ignoreCase = true) || match.value.contains("WARNING", ignoreCase = true) || match.value.contains("DANGER", ignoreCase = true)
        val color = if (isWarning) errorColor else Color.Unspecified
        
        withStyle(style = SpanStyle(fontWeight = FontWeight.Bold, color = color)) {
            append(match.groupValues[1])
        }
        currentIndex = match.range.last + 1
    }
    if (currentIndex < text.length) {
        append(text.substring(currentIndex))
    }
}

@Composable
fun LegaleseLensTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFF818CF8), // Indigo 400
            onPrimary = Color.White,
            secondary = Color(0xFF34D399), // Emerald 400
            background = Color(0xFF0F172A), // Slate 900
            surface = Color(0xFF1E293B), // Slate 800
            onSurface = Color.White
        ),
        content = content
    )
}

@Composable
fun LegaleseLensApp(apiKey: String) {
    var currentScreen by remember { mutableStateOf(MainActivity.AppScreen.SPLASH) }
    var scanMode by remember { mutableStateOf(MainActivity.ScanMode.COMPARE) }
    var scanState by remember { mutableStateOf(MainActivity.ScanState.IDLE) }
    var contractOneText by remember { mutableStateOf<String?>(null) }
    var contractTwoText by remember { mutableStateOf<String?>(null) }
    var comparisonResult by remember { mutableStateOf<String?>(null) }
    var selectedHistoryItem by remember { mutableStateOf<HistoryItem?>(null) }
    
    val context = LocalContext.current
    val sharedPreferences = context.getSharedPreferences("LegaleseLensPrefs", android.content.Context.MODE_PRIVATE)
    
    val historyList = remember { 
        val list = mutableStateListOf<HistoryItem>()
        val historyJson = sharedPreferences.getString("history_list", "[]")
        try {
            val jsonArray = org.json.JSONArray(historyJson)
            for (i in 0 until jsonArray.length()) {
                val obj = jsonArray.getJSONObject(i)
                list.add(HistoryItem(
                    timestamp = obj.getLong("timestamp"),
                    snippet = obj.getString("snippet"),
                    fullText = obj.optString("fullText", obj.getString("snippet")),
                    title = obj.optString("title", "Scan Result")
                ))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        list
    }
    
    fun saveHistory() {
        try {
            val jsonArray = org.json.JSONArray()
            for (item in historyList) {
                val obj = org.json.JSONObject()
                obj.put("timestamp", item.timestamp)
                obj.put("snippet", item.snippet)
                obj.put("fullText", item.fullText)
                obj.put("title", item.title)
                jsonArray.put(obj)
            }
            sharedPreferences.edit().putString("history_list", jsonArray.toString()).apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    val lifecycleOwner = LocalLifecycleOwner.current
    val coroutineScope = rememberCoroutineScope()
    var previewViewRef by remember { mutableStateOf<PreviewView?>(null) }
    val isInspectionMode = LocalInspectionMode.current

    fun resetApp() {
        scanState = MainActivity.ScanState.IDLE
        contractOneText = null
        contractTwoText = null
        comparisonResult = null
    }

    suspend fun processImage(bitmap: Bitmap): String? = suspendCancellableCoroutine { continuation ->
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val image = InputImage.fromBitmap(bitmap, 0)
        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val text = visionText.text
                continuation.resume(text.ifBlank { null })
            }
            .addOnFailureListener {
                continuation.resume(null)
            }
    }

    fun scanDocument() {
        val bitmap = previewViewRef?.bitmap
        if (bitmap == null) {
            Toast.makeText(context, "Camera not ready", Toast.LENGTH_SHORT).show()
            return
        }

        if (scanState == MainActivity.ScanState.IDLE) {
            scanState = MainActivity.ScanState.SCANNING_FIRST
        } else if (scanState == MainActivity.ScanState.FIRST_SCANNED) {
            scanState = MainActivity.ScanState.SCANNING_SECOND
        }
        
        coroutineScope.launch {
            val text = processImage(bitmap)
            if (text == null) {
                Toast.makeText(context, "No text found!", Toast.LENGTH_SHORT).show()
                if (scanState == MainActivity.ScanState.SCANNING_FIRST) scanState = MainActivity.ScanState.IDLE
                if (scanState == MainActivity.ScanState.SCANNING_SECOND) scanState = MainActivity.ScanState.FIRST_SCANNED
                return@launch
            }
            if (scanState == MainActivity.ScanState.SCANNING_FIRST) {
                if (scanMode == MainActivity.ScanMode.SINGLE) {
                    val prompt = """
                        Analyze this legal document.
                        Format your response clearly using the following structure:
                        # 🔍 SUMMARY
                        A 2-sentence overview of the document's purpose.
                        
                        ## ✅ ADVANTAGES / GOOD POINTS
                        List 2-3 benefits or protections this document offers to the user.
                        
                        ## ⚠️ DISADVANTAGES / HIGH RISKS
                        List 2-3 risks, loopholes, or dangerous clauses. Use emojis.
                        
                        ### 💰 FINANCIALS
                        Clear explanation of any costs, fees, or penalties mentioned.
                        
                        ### 💡 RECOMMENDATION
                        One clear piece of advice before signing or agreeing.
                        
                        Keep it concise and easy to read on a mobile screen.
                        
                        DOCUMENT: $text
                    """.trimIndent()
                    
                    scanState = MainActivity.ScanState.COMPARING
                    
                    val modelIdsToTry = listOf(
                        "gemini-1.5-flash",
                        "gemini-2.0-flash",
                        "gemini-flash-lite-latest",
                        "gemini-1.5-flash-8b",
                        "gemini-2.0-flash-lite"
                    )

                    var success = false
                    for (modelId in modelIdsToTry) {
                        try {
                            val model = GenerativeModel(modelId, apiKey)
                            val response = model.generateContent(prompt)
                            comparisonResult = response.text ?: "No response generated."
                            success = true
                            break
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                    
                    if (!success) {
                        comparisonResult = "Analysis failed. Please check your internet or API key quota."
                    }
                    
                    // Save to history on success
                    if (success && comparisonResult != null) {
                        val summaryLines = comparisonResult!!.split("\n").filter { it.contains("SUMMARY", ignoreCase = true) }
                        val snippet = summaryLines.firstOrNull() ?: (comparisonResult!!.take(100) + "...")
                        historyList.add(0, HistoryItem(System.currentTimeMillis(), snippet, comparisonResult!!, "Single Doc Analysis"))
                        saveHistory()
                    }
                    
                    scanState = MainActivity.ScanState.RESULTS
                    return@launch
                }

                contractOneText = text
                scanState = MainActivity.ScanState.FIRST_SCANNED
            } else if (scanState == MainActivity.ScanState.SCANNING_SECOND) {
                contractTwoText = text
                scanState = MainActivity.ScanState.COMPARING
                
                val prompt = """
                    Compare these two contracts and highlight risks/differences.
                    Format your response clearly using the following structure:
                    # 🔍 SUMMARY
                    A 2-sentence overview of the main difference.
                    
                    ## ⚠️ HIGH RISKS
                    Use bullet points for dangerous clauses.
                    
                    ### 💰 FINANCIALS
                    Clear comparison of costs, fees, or penalties.
                    
                    ### ✅ RECOMMENDATION
                    One clear piece of advice.
                    
                    Keep it concise and easy to read on a mobile screen.
                    
                    DOC A: $contractOneText
                    DOC B: $contractTwoText
                """.trimIndent()

                val modelIdsToTry = listOf(
                    "gemini-1.5-flash",
                    "gemini-2.0-flash",
                    "gemini-flash-lite-latest",
                    "gemini-1.5-flash-8b",
                    "gemini-2.0-flash-lite"
                )

                var success = false
                for (modelId in modelIdsToTry) {
                    try {
                        val model = GenerativeModel(modelId, apiKey)
                        val response = model.generateContent(prompt)
                        comparisonResult = response.text ?: "No response generated."
                        success = true
                        break
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
                
                if (!success) {
                    comparisonResult = "Comparison failed. Please check your internet or API key quota."
                }
                
                // Save to history on success
                if (success && comparisonResult != null) {
                    val summaryLines = comparisonResult!!.split("\n").filter { it.contains("SUMMARY", ignoreCase = true) }
                    val snippet = summaryLines.firstOrNull() ?: (comparisonResult!!.take(100) + "...")
                    historyList.add(0, HistoryItem(System.currentTimeMillis(), snippet, comparisonResult!!, "Document Comparison"))
                    saveHistory()
                }
                
                scanState = MainActivity.ScanState.RESULTS
            }
        }
    }

    // --- SCREEN NAVIGATION ---
    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = {
            fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
        },
        label = "AppScreenTransition"
    ) { screen ->
        when (screen) {
            MainActivity.AppScreen.SPLASH -> {
                SplashScreen(onTimeout = { currentScreen = MainActivity.AppScreen.DASHBOARD })
            }
            MainActivity.AppScreen.DASHBOARD -> {
                DashboardScreen(
                    onNavigateToScanCompare = { 
                        scanMode = MainActivity.ScanMode.COMPARE
                        currentScreen = MainActivity.AppScreen.CAMERA_SCAN 
                    },
                    onNavigateToScanSingle = {
                        scanMode = MainActivity.ScanMode.SINGLE
                        currentScreen = MainActivity.AppScreen.CAMERA_SCAN
                    },
                    onNavigateToHistory = { currentScreen = MainActivity.AppScreen.HISTORY },
                    onNavigateToDictionary = { currentScreen = MainActivity.AppScreen.DICTIONARY },
                    onNavigateToTemplates = { currentScreen = MainActivity.AppScreen.TEMPLATES },
                    onNavigateToAbout = { currentScreen = MainActivity.AppScreen.ABOUT }
                )
            }
            MainActivity.AppScreen.CAMERA_SCAN -> {
                CameraScanScreen(
                    scanState = scanState,
                    comparisonResult = comparisonResult,
                    isInspectionMode = isInspectionMode,
                    lifecycleOwner = lifecycleOwner,
                    onBack = { 
                        currentScreen = MainActivity.AppScreen.DASHBOARD 
                        resetApp()
                    },
                    onReset = { resetApp() },
                    onScanDocument = { scanDocument() },
                    onPreviewReady = { previewViewRef = it }
                )
            }
            MainActivity.AppScreen.HISTORY -> {
                HistoryScreen(
                    historyList = historyList,
                    onBack = { currentScreen = MainActivity.AppScreen.DASHBOARD },
                    onItemClick = { 
                        selectedHistoryItem = it
                        currentScreen = MainActivity.AppScreen.HISTORY_DETAIL
                    }
                )
            }
            MainActivity.AppScreen.HISTORY_DETAIL -> {
                HistoryDetailScreen(
                    item = selectedHistoryItem,
                    onBack = { currentScreen = MainActivity.AppScreen.HISTORY }
                )
            }
            MainActivity.AppScreen.DICTIONARY -> {
                DictionaryScreen(onBack = { currentScreen = MainActivity.AppScreen.DASHBOARD })
            }
            MainActivity.AppScreen.TEMPLATES -> {
                TemplatesScreen(onBack = { currentScreen = MainActivity.AppScreen.DASHBOARD })
            }
            MainActivity.AppScreen.ABOUT -> {
                AboutScreen(onBack = { currentScreen = MainActivity.AppScreen.DASHBOARD })
            }
        }
    }
}

data class HistoryItem(val timestamp: Long, val snippet: String, val fullText: String, val title: String)

// --- PRESENTATION SCREENS ---

@Composable
fun SplashScreen(onTimeout: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2000)
        onTimeout()
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color(0xFF0F172A), Color(0xFF1E293B))
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                imageVector = Icons.Rounded.AccountBalance,
                contentDescription = "Logo",
                modifier = Modifier.size(100.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "LegaleseLens",
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Your AI-Powered Legal Assistant",
                style = MaterialTheme.typography.bodyLarge,
                color = Color.LightGray
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    onNavigateToScanCompare: () -> Unit,
    onNavigateToScanSingle: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToDictionary: () -> Unit,
    onNavigateToTemplates: () -> Unit,
    onNavigateToAbout: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Rounded.AccountBalance,
                            contentDescription = "Logo",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("LegaleseLens", fontWeight = FontWeight.Bold) 
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.Start
        ) {
            Text(
                text = "Welcome back!",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "What would you like to analyze today?",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Main Actions
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Card(
                    onClick = onNavigateToScanSingle,
                    modifier = Modifier
                        .weight(1f)
                        .height(160.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.DocumentScanner,
                            contentDescription = "Scan Single",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Analyze Single Doc",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    }
                }

                Card(
                    onClick = onNavigateToScanCompare,
                    modifier = Modifier
                        .weight(1f)
                        .height(160.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        verticalArrangement = Arrangement.Center,
                        horizontalAlignment = Alignment.Start
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.CompareArrows,
                            contentDescription = "Compare",
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onSecondary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "Compare Two Docs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondary
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            
            Text(
                text = "Quick Tools",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            
            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SmallDashboardCard(
                    title = "History",
                    icon = Icons.Rounded.History,
                    onClick = onNavigateToHistory,
                    modifier = Modifier.weight(1f)
                )
                SmallDashboardCard(
                    title = "About",
                    icon = Icons.Rounded.Info,
                    onClick = onNavigateToAbout,
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                SmallDashboardCard(
                    title = "Templates",
                    icon = Icons.AutoMirrored.Rounded.LibraryBooks,
                    onClick = onNavigateToTemplates,
                    modifier = Modifier.weight(1f)
                )
                SmallDashboardCard(
                    title = "Dictionary",
                    icon = Icons.AutoMirrored.Rounded.MenuBook,
                    onClick = onNavigateToDictionary,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SmallDashboardCard(title: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Card(
        onClick = onClick,
        modifier = modifier.height(100.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(28.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScanScreen(
    scanState: MainActivity.ScanState,
    comparisonResult: String?,
    isInspectionMode: Boolean,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    onBack: () -> Unit,
    onReset: () -> Unit,
    onScanDocument: () -> Unit,
    onPreviewReady: (PreviewView) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scanner") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (scanState != MainActivity.ScanState.IDLE) {
                        IconButton(onClick = onReset) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Reset")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                    actionIconContentColor = Color.White
                )
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).background(Color.Black)) {
            // Camera Preview Content
            if (isInspectionMode) {
                Box(
                    modifier = Modifier.fillMaxSize().background(Color.DarkGray),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Camera Preview Placeholder", color = Color.White)
                }
            } else {
                AndroidView(
                    factory = { ctx ->
                        val previewView = PreviewView(ctx).apply {
                            this.scaleType = PreviewView.ScaleType.FILL_CENTER
                        }
                        val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
                        cameraProviderFuture.addListener({
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            try {
                                cameraProvider.unbindAll()
                                cameraProvider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
                            } catch (e: Exception) {
                                Toast.makeText(ctx, "Camera error", Toast.LENGTH_SHORT).show()
                            }
                        }, ContextCompat.getMainExecutor(ctx))
                        
                        onPreviewReady(previewView)
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

            // Bottom Sheet Overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
            ) {
                AnimatedContent(
                    targetState = scanState,
                    transitionSpec = {
                        slideInVertically(
                            animationSpec = tween(500),
                            initialOffsetY = { fullHeight -> fullHeight }
                        ) togetherWith slideOutVertically(
                            animationSpec = tween(500),
                            targetOffsetY = { fullHeight -> fullHeight }
                        )
                    }, label = "BottomSheetAnimation"
                ) { state ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)),
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.95f),
                        tonalElevation = 8.dp
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp)
                                .animateContentSize(),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            when (state) {
                                MainActivity.ScanState.IDLE -> {
                                    Icon(
                                        imageVector = Icons.Rounded.Search,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        "Scan First Document",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Position the first contract in the frame to begin the comparison.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Button(
                                        onClick = onScanDocument,
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = RoundedCornerShape(16.dp)
                                    ) {
                                        Text("Capture Document 1", fontSize = 16.sp)
                                    }
                                }
                                MainActivity.ScanState.SCANNING_FIRST -> {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Reading document...", style = MaterialTheme.typography.titleMedium)
                                }
                                MainActivity.ScanState.FIRST_SCANNED -> {
                                    Icon(
                                        imageVector = Icons.Rounded.CheckCircle,
                                        contentDescription = null,
                                        modifier = Modifier.size(48.dp),
                                        tint = MaterialTheme.colorScheme.secondary
                                    )
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text(
                                        "Document 1 Saved!",
                                        style = MaterialTheme.typography.titleLarge,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Now scan the second document.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Button(
                                        onClick = onScanDocument,
                                        modifier = Modifier.fillMaxWidth().height(56.dp),
                                        shape = RoundedCornerShape(16.dp),
                                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                                    ) {
                                        Text("Capture Document 2", fontSize = 16.sp)
                                    }
                                }
                                MainActivity.ScanState.SCANNING_SECOND -> {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.secondary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Reading document...", style = MaterialTheme.typography.titleMedium)
                                }
                                MainActivity.ScanState.COMPARING -> {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                    Spacer(modifier = Modifier.height(16.dp))
                                    Text("Analyzing risks...", style = MaterialTheme.typography.titleMedium)
                                }
                                MainActivity.ScanState.RESULTS -> {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .heightIn(max = 500.dp)
                                    ) {
                                        Text(
                                            "Comparison Results",
                                            style = MaterialTheme.typography.headlineSmall,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.padding(bottom = 16.dp)
                                        )
                                        
                                        Box(
                                            modifier = Modifier
                                                .weight(1f, fill = false)
                                                .verticalScroll(rememberScrollState())
                                                .background(
                                                    MaterialTheme.colorScheme.background,
                                                    RoundedCornerShape(12.dp)
                                                )
                                                .padding(16.dp)
                                        ) {
                                            Text(
                                                text = parseMarkdownToAnnotatedString(comparisonResult ?: "No results", MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error),
                                                style = MaterialTheme.typography.bodyLarge,
                                                lineHeight = 24.sp
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(16.dp))
                                        
                                        Button(
                                            onClick = onReset,
                                            modifier = Modifier.fillMaxWidth().height(56.dp),
                                            shape = RoundedCornerShape(16.dp)
                                        ) {
                                            Text("Scan New Documents", fontSize = 16.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(historyList: List<HistoryItem>, onBack: () -> Unit, onItemClick: (HistoryItem) -> Unit) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM dd, yyyy - HH:mm", java.util.Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Recent Scans") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (historyList.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding), contentAlignment = Alignment.Center) {
                Text("No past scans found.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(innerPadding).verticalScroll(rememberScrollState())) {
                historyList.forEach { item ->
                    Card(
                        onClick = { onItemClick(item) },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.title,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = dateFormat.format(java.util.Date(item.timestamp)),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = parseMarkdownToAnnotatedString(item.snippet, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error),
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 3,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("About") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Rounded.AccountBalance,
                contentDescription = "Logo",
                modifier = Modifier.size(80.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "LegaleseLens",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Version 1.0.0",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(32.dp))
            Text(
                text = "LegaleseLens is an AI-powered legal document translator and risk highlighter app. Use it to easily scan through long legal contracts, detect non-standard or dangerous clauses, and get simplified explanations tailored for peace of mind before signing any document.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryDetailScreen(item: HistoryItem?, onBack: () -> Unit) {
    val dateFormat = remember { java.text.SimpleDateFormat("MMM dd, yyyy - HH:mm", java.util.Locale.getDefault()) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(item?.title ?: "Scan Detail") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            if (item != null) {
                Text(
                    text = "Scanned on ${dateFormat.format(java.util.Date(item.timestamp))}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Text(
                    text = parseMarkdownToAnnotatedString(item.fullText, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.error),
                    style = MaterialTheme.typography.bodyLarge,
                    lineHeight = 24.sp
                )
            } else {
                Text("Item details not found.")
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DictionaryScreen(onBack: () -> Unit) {
    val terms = listOf(
        "Force Majeure" to "An unforeseeable circumstance that prevents someone from fulfilling a contract (like a natural disaster).",
        "Indemnification" to "A promise to compensate someone for harm or loss. Essentially, 'If I cause a problem, I will pay to fix it.'",
        "Boilerplate" to "Standardized text or clauses found in almost every contract, usually at the end, dealing with general administrative matters.",
        "Severability" to "A clause stating that if one part of the contract is illegal or unenforceable, the rest of the contract still applies.",
        "Waiver" to "Voluntarily giving up a known right. E.g., if a landlord doesn't charge a late fee one month, they might 'waive' their right to it.",
        "Arbitration" to "A way to resolve disputes outside of court using a neutral third party (an arbitrator) whose decision is often final.",
        "Limitation of Liability" to "A cap on how much money one party can be forced to pay the other if something goes wrong.",
        "Breach of Contract" to "Failing to perform any term of a contract without a legitimate legal excuse."
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Legal Dictionary") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Legal jargon is often confusing. Use this dictionary to understand common terms found in standard contracts.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            terms.forEach { (term, definition) ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = term,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = definition,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TemplatesScreen(onBack: () -> Unit) {
    val templates = listOf(
        "Non-Disclosure Agreement (NDA)" to "Used to protect confidential information. Important clauses include definition of confidential info, exclusions, and term (how long the secret must be kept).",
        "Residential Lease Agreement" to "Used for renting a home. Important clauses include rent amount/due date, security deposit rules, maintenance responsibilities, and early termination penalties.",
        "Employment Contract" to "Used when hiring a new employee. Important clauses include compensation, benefits, at-will vs term duration, and non-compete/non-solicitation agreements.",
        "Terms of Service (ToS)" to "Used for software or websites. Explains the rules for users. Important clauses often include limitation of liability, user data rights, and arbitration requirements."
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Standard Templates") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
        ) {
            Box(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Understand what standard contracts typically consist of so you have a baseline for comparison when reading new ones.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            templates.forEach { (template, detail) ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = template,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = detail,
                            style = MaterialTheme.typography.bodyMedium,
                            lineHeight = 20.sp
                        )
                    }
                }
            }
        }
    }
}
