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
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalLifecycleOwner
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
import com.google.ai.client.generativeai.GenerativeModel
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

class MainActivity : ComponentActivity() {
    private val GEMINI_API_KEY = "API KEY"
    
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
                LegaleseLensApp(GEMINI_API_KEY)
            }
        }
    }
}

fun parseMarkdownToAnnotatedString(text: String): AnnotatedString {
    return buildAnnotatedString {
        val parts = text.split("**")
        for (i in parts.indices) {
            if (i % 2 == 1) {
                withStyle(style = SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(parts[i])
                }
            } else {
                append(parts[i])
            }
        }
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
    var scanState by remember { mutableStateOf(MainActivity.ScanState.IDLE) }
    var contractOneText by remember { mutableStateOf<String?>(null) }
    var contractTwoText by remember { mutableStateOf<String?>(null) }
    var comparisonResult by remember { mutableStateOf<String?>(null) }
    
    val context = LocalContext.current
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
                continuation.resume(if (text.isBlank()) null else text)
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
                contractOneText = text
                scanState = MainActivity.ScanState.FIRST_SCANNED
            } else if (scanState == MainActivity.ScanState.SCANNING_SECOND) {
                contractTwoText = text
                scanState = MainActivity.ScanState.COMPARING
                
                val prompt = """
                    Compare these two contracts and highlight risks/differences.
                    Format your response clearly using the following structure:
                    1. 🔍 SUMMARY: A 2-sentence overview of the main difference.
                    2. ⚠️ HIGH RISKS: Use emojis and bullet points for dangerous clauses.
                    3. 💰 FINANCIALS: Clear comparison of costs, fees, or penalties.
                    4. ✅ RECOMMENDATION: One clear piece of advice.
                    
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
                scanState = MainActivity.ScanState.RESULTS
            }
        }
    }

    Scaffold(
        floatingActionButton = {
            if (scanState != MainActivity.ScanState.IDLE) {
                FloatingActionButton(
                    onClick = { resetApp() },
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                ) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Reset")
                }
            }
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding).background(Color.Black)) {
            
            if (isInspectionMode) {
                // Show a placeholder in the preview instead of trying to start the camera
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
                        
                        previewViewRef = previewView
                        previewView
                    },
                    modifier = Modifier.fillMaxSize()
                )
            }

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
                                        onClick = { scanDocument() },
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
                                        "Now scan the second document to see the differences and hidden risks.",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                                        textAlign = TextAlign.Center
                                    )
                                    Spacer(modifier = Modifier.height(24.dp))
                                    Button(
                                        onClick = { scanDocument() },
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
                                    Text(
                                        "Analyzing the fine print...",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        "Gemini is comparing both documents for risks.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
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
                                                text = parseMarkdownToAnnotatedString(comparisonResult ?: "No results"),
                                                style = MaterialTheme.typography.bodyLarge,
                                                lineHeight = 24.sp
                                            )
                                        }
                                        
                                        Spacer(modifier = Modifier.height(16.dp))
                                        
                                        Button(
                                            onClick = { resetApp() },
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
