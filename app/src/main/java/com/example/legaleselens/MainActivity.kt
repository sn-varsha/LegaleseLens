package com.example.legaleselens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.text.Html
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.ai.client.generativeai.GenerativeModel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    // Using the key from your list/screenshot
    private val GEMINI_API_KEY = "API KEY"

    private var scanCount = 0
    private var contractOneText: String? = null
    private var contractTwoText: String? = null
    private val CAMERA_PERMISSION_CODE = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE
            )
        }

        val scanButton = findViewById<Button>(R.id.btnCapture)
        scanButton.setOnClickListener {
            Toast.makeText(this, "Scanning document...", Toast.LENGTH_SHORT).show()
            startTextRecognition()
        }

        findViewById<Button>(R.id.btnReset).setOnClickListener {
            resetApp()
        }
    }

    private fun resetApp() {
        scanCount = 0
        contractOneText = null
        contractTwoText = null
        findViewById<TextView>(R.id.txtResults).text = "Memory cleared. Point camera at a contract and tap 'Scan'..."
        findViewById<Button>(R.id.btnCapture).apply {
            text = "Scan Fine Print"
            setBackgroundColor(Color.parseColor("#4CAF50"))
        }
        Toast.makeText(this, "Scanner Reset!", Toast.LENGTH_SHORT).show()
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        baseContext, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider: ProcessCameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(findViewById<androidx.camera.view.PreviewView>(R.id.viewFinder).surfaceProvider)
            }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            } catch(exc: Exception) {
                Toast.makeText(this, "Camera error", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startTextRecognition() {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val viewFinder = findViewById<androidx.camera.view.PreviewView>(R.id.viewFinder)
        val resultTextView = findViewById<TextView>(R.id.txtResults)

        val bitmap = viewFinder.bitmap ?: return
        val image = InputImage.fromBitmap(bitmap, 0)
        resultTextView.text = "Reading document..."

        recognizer.process(image)
            .addOnSuccessListener { visionText ->
                val scannedText = visionText.text
                if (scannedText.isBlank()) {
                    Toast.makeText(this, "No text found!", Toast.LENGTH_SHORT).show()
                    return@addOnSuccessListener
                }

                scanCount++
                if (scanCount == 1) {
                    contractOneText = scannedText
                    resultTextView.text = "✅ Doc 1 Saved!\n\nScan the second document now."
                    findViewById<Button>(R.id.btnCapture).apply {
                        text = "Scan Second Doc"
                        setBackgroundColor(Color.BLUE)
                    }
                } else {
                    contractTwoText = scannedText
                    resultTextView.text = "⚖️ Comparing... please wait."
                    compareContracts()
                    scanCount = 0
                    findViewById<Button>(R.id.btnCapture).apply {
                        text = "Scan Fine Print"
                        setBackgroundColor(Color.parseColor("#4CAF50"))
                    }
                }
            }
    }

    private fun compareContracts() {
        val resultTextView = findViewById<TextView>(R.id.txtResults)
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

        MainScope().launch {
            val modelIdsToTry = listOf(
                "gemini-1.5-flash",
                "gemini-2.0-flash",
                "gemini-flash-lite-latest",
                "gemini-1.5-flash-8b",
                "gemini-2.0-flash-lite"
            )

            for (modelId in modelIdsToTry) {
                try {
                    val model = GenerativeModel(modelId, GEMINI_API_KEY)
                    val response = model.generateContent(prompt)
                    val rawText = response.text ?: ""
                    
                    // Simple Markdown-to-HTML conversion for bolding
                    val formattedText = rawText
                        .replace(Regex("\\*\\*(.*?)\\*\\*"), "<b>$1</b>")
                        .replace("\n", "<br>")
                    
                    resultTextView.text = Html.fromHtml(formattedText, Html.FROM_HTML_MODE_COMPACT)
                    return@launch
                } catch (e: Exception) {
                    // Continue to next model
                }
            }
            resultTextView.text = "Comparison failed. Please check your internet or API quota."
        }
    }
}
