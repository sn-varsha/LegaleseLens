package com.example.legaleselens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
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
        val prompt = "Compare these two contracts and highlight risks/differences:\n\nDOC A: $contractOneText\n\nDOC B: $contractTwoText"

        MainScope().launch {
            // Trying potential IDs from your list in order of stability
            val modelIdsToTry = listOf(
                "gemini-1.5-flash",        // Standard ID for "Gemini Flash Latest"
                "gemini-2.0-flash",        // ID from your list
                "gemini-flash-lite-latest", // ID from your list
                "gemini-1.5-flash-8b",     // High availability Lite version
                "gemini-2.0-flash-lite"    // Another ID from your list
            )

            val errors = StringBuilder()
            
            for (modelId in modelIdsToTry) {
                try {
                    val model = GenerativeModel(modelId, GEMINI_API_KEY)
                    val response = model.generateContent(prompt)
                    resultTextView.text = response.text
                    return@launch // SUCCESS! Exit the loop and the coroutine
                } catch (e: Exception) {
                    val msg = e.message ?: "Unknown error"
                    // If it's a 404, we just log it and move to next. If it's quota (429), it might be worth mentioning.
                    errors.append("- $modelId: ${if (msg.contains("404")) "Not Found" else msg}\n")
                }
            }

            // If we get here, ALL models failed
            resultTextView.text = "Comparison failed for all models.\n\nDetails:\n$errors"
        }
    }
}
