package com.sladkaya.app.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.sladkaya.app.R
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Camera-only boundary. It returns one bounded, case-preserved DataMatrix payload
 * after two matching frames; identity resolution happens outside the camera layer.
 */
class DataMatrixScannerActivity : ComponentActivity() {
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val frameInFlight = AtomicBoolean(false)
    private val resultDelivered = AtomicBoolean(false)
    private val cameraStarted = AtomicBoolean(false)
    private val consensus = DataMatrixScanConsensus()

    private lateinit var previewView: PreviewView
    private var cameraProvider: ProcessCameraProvider? = null
    private var scanner: BarcodeScanner? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            startCamera()
        } else {
            finishWithError(ERROR_CAMERA_PERMISSION_REQUIRED)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(Activity.RESULT_CANCELED)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
        setContentView(scannerContent(previewView))

        if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) {
            finishWithError(ERROR_CAMERA_UNAVAILABLE)
            return
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun scannerContent(preview: PreviewView): FrameLayout = FrameLayout(this).apply {
        setBackgroundColor(Color.BLACK)
        addView(
            preview,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        addView(
            TextView(context).apply {
                text = getString(R.string.data_matrix_scanner_instruction)
                setTextColor(Color.WHITE)
                setBackgroundColor(0xB3000000.toInt())
                gravity = Gravity.CENTER
                textSize = 16f
                setPadding(32, 24, 32, 24)
            },
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM,
            ),
        )
    }

    private fun startCamera() {
        if (!cameraStarted.compareAndSet(false, true) || resultDelivered.get()) return
        scanner = runCatching {
            BarcodeScanning.getClient(
                BarcodeScannerOptions.Builder()
                    .setBarcodeFormats(Barcode.FORMAT_DATA_MATRIX)
                    .build(),
            )
        }.getOrElse {
            finishWithError(ERROR_SCANNER_UNAVAILABLE)
            return
        }
        val providerFuture = runCatching {
            ProcessCameraProvider.getInstance(this)
        }.getOrElse {
            finishWithError(ERROR_CAMERA_START_FAILED)
            return
        }
        providerFuture.addListener(
            {
                if (isDestroyed || isFinishing || resultDelivered.get()) return@addListener
                val provider = runCatching { providerFuture.get() }.getOrElse {
                    finishWithError(ERROR_CAMERA_START_FAILED)
                    return@addListener
                }
                cameraProvider = provider
                bindCamera(provider)
            },
            ContextCompat.getMainExecutor(this),
        )
    }

    @OptIn(markerClass = [ExperimentalGetImage::class])
    private fun bindCamera(provider: ProcessCameraProvider) {
        val selector = when {
            runCatching { provider.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }
                .getOrDefault(false) -> CameraSelector.DEFAULT_BACK_CAMERA
            runCatching { provider.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }
                .getOrDefault(false) -> CameraSelector.DEFAULT_FRONT_CAMERA
            else -> {
                finishWithError(ERROR_CAMERA_UNAVAILABLE)
                return
            }
        }
        val preview = Preview.Builder().build().also { useCase ->
            useCase.surfaceProvider = previewView.surfaceProvider
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase -> useCase.setAnalyzer(cameraExecutor, ::analyze) }

        runCatching {
            provider.unbindAll()
            provider.bindToLifecycle(this, selector, preview, analysis)
        }.onFailure {
            finishWithError(ERROR_CAMERA_START_FAILED)
        }
    }

    @ExperimentalGetImage
    private fun analyze(imageProxy: ImageProxy) {
        if (resultDelivered.get() || !frameInFlight.compareAndSet(false, true)) {
            imageProxy.close()
            return
        }
        val mediaImage = imageProxy.image
        val activeScanner = scanner
        if (mediaImage == null || activeScanner == null) {
            consensus.observe(emptyList())
            frameInFlight.set(false)
            imageProxy.close()
            return
        }

        val image = runCatching {
            InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
        }.getOrElse {
            consensus.observe(emptyList())
            frameInFlight.set(false)
            imageProxy.close()
            return
        }
        val task = runCatching { activeScanner.process(image) }.getOrElse {
            consensus.observe(emptyList())
            frameInFlight.set(false)
            imageProxy.close()
            return
        }
        task.addOnSuccessListener { barcodes ->
            when (val decision = consensus.observe(barcodes.map(Barcode::getRawValue))) {
                is DataMatrixScanDecision.Confirmed -> finishWithRawValue(decision.rawValue)
                DataMatrixScanDecision.AlreadyConfirmed,
                DataMatrixScanDecision.AwaitingConfirmation,
                -> Unit
            }
        }.addOnFailureListener {
            consensus.observe(emptyList())
        }.addOnCompleteListener {
            frameInFlight.set(false)
            imageProxy.close()
        }
    }

    private fun finishWithRawValue(rawValue: String) {
        if (isFinishing || isDestroyed) return
        if (!resultDelivered.compareAndSet(false, true)) return
        runOnUiThread {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(EXTRA_RAW_VALUE, rawValue),
            )
            closeCapture()
            finish()
        }
    }

    private fun finishWithError(error: String) {
        if (isFinishing || isDestroyed) return
        if (!resultDelivered.compareAndSet(false, true)) return
        runOnUiThread {
            setResult(
                Activity.RESULT_CANCELED,
                Intent().putExtra(EXTRA_ERROR, error),
            )
            closeCapture()
            finish()
        }
    }

    private fun closeCapture() {
        runCatching { cameraProvider?.unbindAll() }
        cameraProvider = null
        val activeScanner = scanner
        scanner = null
        runCatching { activeScanner?.close() }
    }

    override fun onDestroy() {
        resultDelivered.set(true)
        closeCapture()
        cameraExecutor.shutdown()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        super.onDestroy()
    }

    companion object {
        const val EXTRA_RAW_VALUE = "com.sladkaya.app.extra.DATA_MATRIX_RAW_VALUE"
        const val EXTRA_ERROR = "com.sladkaya.app.extra.DATA_MATRIX_ERROR"

        const val ERROR_CAMERA_PERMISSION_REQUIRED = "CAMERA_PERMISSION_REQUIRED"
        const val ERROR_CAMERA_UNAVAILABLE = "CAMERA_UNAVAILABLE"
        const val ERROR_CAMERA_START_FAILED = "CAMERA_START_FAILED"
        const val ERROR_SCANNER_UNAVAILABLE = "DATA_MATRIX_SCANNER_UNAVAILABLE"
    }
}
