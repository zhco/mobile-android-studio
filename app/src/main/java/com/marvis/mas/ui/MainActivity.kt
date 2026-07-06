package com.marvis.mas.ui

import android.annotation.SuppressLint
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.marvis.mas.MASApplication
import com.marvis.mas.R
import com.marvis.mas.databinding.ActivityMainBinding
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val app: MASApplication get() = MASApplication.instance

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Full screen immersive
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupWebView()
        requestStoragePermission()
        startCodeServer()
    }

    private fun requestStoragePermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!android.os.Environment.isExternalStorageManager()) {
                Toast.makeText(this, "Grant 'All files access' for project storage", Toast.LENGTH_LONG).show()
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                manageStorageLauncher.launch(intent)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        binding.webView.apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = true
                allowContentAccess = true
                cacheMode = WebSettings.LOAD_NO_CACHE
                setSupportMultipleWindows(false)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                // Improve performance
                setRenderPriority(WebSettings.RenderPriority.HIGH)
            }

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    binding.progressBar.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainMenu == true) {
                        binding.progressBar.visibility = View.GONE
                        binding.errorText.visibility = View.VISIBLE
                        binding.errorText.text = "Failed to load: ${error?.description}"
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    if (newProgress < 100) {
                        binding.progressBar.progress = newProgress
                    }
                }
            }
        }
    }

    private fun startCodeServer() {
        binding.statusText.text = "Extracting assets..."
        binding.progressBar.visibility = View.VISIBLE

        lifecycleScope.launch {
            // Step 1: Extract code-server assets
            val extractResult = app.codeServerManager.extractAssets { msg: String ->
                runOnUiThread { binding.statusText.text = msg }
            }

            if (extractResult.isFailure) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.errorText.visibility = View.VISIBLE
                    binding.errorText.text = "Failed: ${extractResult.exceptionOrNull()?.message}"
                }
                return@launch
            }

            // Step 2: Extract Android SDK
            binding.statusText.text = "Extracting SDK..."
            val sdkResult = app.buildManager.extractSdk { msg: String ->
                runOnUiThread { binding.statusText.text = msg }
            }

            if (sdkResult.isFailure) {
                runOnUiThread {
                    binding.statusText.text = "SDK extraction failed, builds may not work"
                }
            }

            // Step 3: Start code-server
            binding.statusText.text = "Starting code-server..."
            val startResult = app.codeServerManager.start { msg: String ->
                runOnUiThread {
                    binding.statusText.text = msg
                    if (msg.contains("ready")) {
                        binding.webView.loadUrl(app.codeServerManager.url)
                        // Start foreground service to keep alive
                        startForegroundService(Intent(this@MainActivity, com.marvis.mas.server.CodeServerService::class.java))
                    }
                }
            }

            if (startResult.isFailure) {
                runOnUiThread {
                    binding.progressBar.visibility = View.GONE
                    binding.errorText.visibility = View.VISIBLE
                    binding.errorText.text = "Failed to start server: ${startResult.exceptionOrNull()?.message}"
                }
            }
        }
    }

    override fun onDestroy() {
        // Don't stop code-server on destroy — foreground service keeps it alive
        super.onDestroy()
    }

    @Deprecated("Use onBackPressedDispatcher instead")
    override fun onBackPressed() {
        if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
