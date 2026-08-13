package com.skywell.skydash

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var vehicleService: VehicleDataService? = null
    private var isBound = false
    private val gson = Gson()

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(className: ComponentName, service: IBinder) {
            val binder = service as VehicleDataService.LocalBinder
            vehicleService = binder.getService()
            isBound = true
            Log.i("MainActivity", "Bound to VehicleDataService successfully.")

            // Hook up data updates
            vehicleService?.onDataUpdated = { jsonData ->
                updateDashboard(jsonData)
            }
        }

        override fun onServiceDisconnected(arg0: ComponentName) {
            isBound = false
            vehicleService = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        webView = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                useWideViewPort = true
                loadWithOverviewMode = true
                allowFileAccess = true
                allowContentAccess = true
            }
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    Log.i("MainActivity", "WebView finished loading assets/index.html")
                }
            }
            
            // Register JavaScript bridge under name 'Android'
            addJavascriptInterface(WebAppInterface(this@MainActivity), "Android")
            loadUrl("file:///android_asset/index.html")
        }
        
        setContentView(webView)

        // Start and Bind to background vehicle service
        val intent = Intent(this, VehicleDataService::class.java)
        startService(intent)
        bindService(intent, connection, Context.BIND_AUTO_CREATE)
    }
    
    override fun onResume() {
        super.onResume()
        // Hide status bar but keep navigation keys visible
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
    }

    fun updateDashboard(jsonData: String) {
        runOnUiThread {
            webView.evaluateJavascript("javascript:updateData($jsonData)", null)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isBound) {
            unbindService(connection)
            isBound = false
        }
    }

    // Inner bridge class to handle calls from JavaScript
    inner class WebAppInterface(private val context: Context) {
        
        @android.webkit.JavascriptInterface
        fun onReady() {
            Log.i("WebAppInterface", "JS Page reported ready.")
        }

        @android.webkit.JavascriptInterface
        fun resetTrip(id: String) {
            Log.i("WebAppInterface", "Requesting reset for Trip: $id")
            vehicleService?.manualResetTrip(id)
        }

        @android.webkit.JavascriptInterface
        fun saveSettings(settingsJson: String) {
            try {
                Log.i("WebAppInterface", "Saving user price settings: $settingsJson")
                val type = object : TypeToken<Map<String, Float>>() {}.type
                val pricesMap: Map<String, Float> = gson.fromJson(settingsJson, type)
                
                val acVal = pricesMap["acPrice"] ?: 2.20f
                val dcVal = pricesMap["dcPrice"] ?: 8.50f

                val prefs = getSharedPreferences("SkyDashSettings", Context.MODE_PRIVATE)
                prefs.edit()
                    .putFloat("ac_price", acVal)
                    .putFloat("dc_price", dcVal)
                    .apply()
            } catch (e: Exception) {
                Log.e("WebAppInterface", "Error saving settings: ${e.message}")
            }
        }

        @android.webkit.JavascriptInterface
        fun getBlackBoxTelemetry(tripId: Long): String {
            Log.i("WebAppInterface", "Fetching black box telemetry for trip: $tripId")
            val telemetry = vehicleService?.getTelemetryForTrip(tripId) ?: emptyList<Any>()
            return gson.toJson(telemetry)
        }
    }
}
