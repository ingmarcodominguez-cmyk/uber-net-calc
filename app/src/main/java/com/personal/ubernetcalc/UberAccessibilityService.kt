package com.personal.ubernetcalc

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.ImageButton
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Locale
import java.util.regex.Pattern

class UberAccessibilityService : AccessibilityService() {

    private val tag = "UberNetCalcService"
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private lateinit var sharedPreferences: SharedPreferences
    
    // Broadcast receiver for simulation and manual trigger
    private var simulationReceiver: BroadcastReceiver? = null
    
    // Debounce state to avoid multiple calculations for the same card
    private var lastPrice = 0f
    private var lastDistance = 0f
    private var lastCalculationTime = 0L

    // Handler to auto-dismiss overlay
    private val handler = Handler(Looper.getMainLooper())
    private val dismissRunnable = Runnable {
        removeOverlay()
    }

    override fun onCreate() {
        super.onCreate()
        sharedPreferences = getSharedPreferences("UberNetCalcPrefs", Context.MODE_PRIVATE)
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        // Register receiver for simulation
        val filter = IntentFilter("com.personal.ubernetcalc.SIMULATE_TRIP")
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                if (intent != null) {
                    val price = intent.getFloatExtra("price", 0f)
                    val distance = intent.getFloatExtra("distance", 0f)
                    Log.d(tag, "Received simulated trip: price=$price, dist=$distance")
                    showOverlayForCalculatedValues(price, distance, String.format(Locale.US, "%.1f km", distance))
                }
            }
        }
        simulationReceiver = receiver
        // Register receiver with appropriate flags for Android 14/15/16 compatibility
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "Accessibility Service Connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Ignore events originating from our own application to prevent infinite loops and UI lag
        if (event.packageName?.toString() == packageName) return
        
        // Scan for screen changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            
            val texts = mutableListOf<String>()
            
            // 1. Scan all interactive windows (necessary for overlays/floating screens)
            val allWindows = windows
            var scannedAnyWindow = false
            if (!allWindows.isNullOrEmpty()) {
                for (window in allWindows) {
                    val root = window.root
                    if (root != null) {
                        traverseNode(root, texts)
                        root.recycle()
                        scannedAnyWindow = true
                    }
                }
            }
            
            // 2. Fallback to active window if window list was empty
            if (!scannedAnyWindow) {
                val rootNode = rootInActiveWindow
                if (rootNode != null) {
                    traverseNode(rootNode, texts)
                    rootNode.recycle()
                }
            }
            
            // 3. Fallback to event source node
            if (texts.isEmpty()) {
                val sourceNode = event.source
                if (sourceNode != null) {
                    traverseNode(sourceNode, texts)
                    sourceNode.recycle()
                }
            }

            if (texts.isNotEmpty()) {
                parseAndProcessTexts(texts)
            }
        }
    }

    override fun onInterrupt() {
        Log.d(tag, "Service interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        removeOverlay()
        simulationReceiver?.let {
            unregisterReceiver(it)
        }
    }

    // Recursively extracts all texts visible on the screen
    private fun traverseNode(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        
        val nodeText = node.text
        if (nodeText != null && nodeText.isNotEmpty()) {
            texts.add(nodeText.toString())
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseNode(child, texts)
            child?.recycle()
        }
    }

    // Main text parser for Uber screen contents
    private fun parseAndProcessTexts(texts: List<String>) {
        val combinedText = texts.joinToString(" ")
        
        var foundPrice: Float? = null
        val foundDistances = mutableListOf<Float>()

        // Patterns to match ARS prices and distances from the Uber Driver screen
        val pricePattern = Pattern.compile("(?:ARS|AR\\$|\\$)\\s*([\\d\\.,]+)", Pattern.CASE_INSENSITIVE)
        val distancePattern = Pattern.compile("([\\d,\\.]+)\\s*(?:km|kms|kil\\u00f3metros|kilometros)", Pattern.CASE_INSENSITIVE)

        // Find all prices in the combined text
        val priceMatcher = pricePattern.matcher(combinedText)
        while (priceMatcher.find()) {
            val rawPrice = priceMatcher.group(0) ?: ""
            val parsed = cleanPrice(rawPrice)
            if (parsed != null && (foundPrice == null || parsed > foundPrice)) {
                foundPrice = parsed
            }
        }

        // Find all distances in the combined text
        val distanceMatcher = distancePattern.matcher(combinedText)
        while (distanceMatcher.find()) {
            val distanceStr = distanceMatcher.group(1)?.replace(",", ".")
            val parsed = distanceStr?.toFloatOrNull()
            if (parsed != null && !foundDistances.contains(parsed)) {
                foundDistances.add(parsed)
            }
        }

        if (foundPrice != null && foundDistances.isNotEmpty()) {
            val totalDistance: Float
            val distanceDetails: String

            if (foundDistances.size >= 2) {
                // Usually first distance is pickup, second is trip
                val pickupDist = foundDistances[0]
                val tripDist = foundDistances[1]
                totalDistance = pickupDist + tripDist
                distanceDetails = String.format(Locale.US, "%.1f km (%.1f + %.1f)", totalDistance, pickupDist, tripDist)
            } else {
                totalDistance = foundDistances[0]
                distanceDetails = String.format(Locale.US, "%.1f km", totalDistance)
            }

            if (totalDistance > 0.05f) {
                // Apply debounce: only trigger calculation if values changed or it's been more than 8 seconds
                val now = System.currentTimeMillis()
                val timeDiff = now - lastCalculationTime
                if (foundPrice != lastPrice || totalDistance != lastDistance || timeDiff > 8000) {
                    lastPrice = foundPrice
                    lastDistance = totalDistance
                    lastCalculationTime = now
                    
                    Log.d(tag, "Detected Uber offer: Price=$foundPrice, TotalDistance=$totalDistance ($distanceDetails)")
                    showOverlayForCalculatedValues(foundPrice, totalDistance, distanceDetails)
                }
            }
        }
    }

    private fun cleanPrice(rawPrice: String): Float? {
        // Remove currency symbols and spaces
        var clean = rawPrice.replace("ARS", "", true)
                            .replace("AR$", "", true)
                            .replace("$", "")
                            .trim()
        
        // If there's a comma followed by exactly two digits at the end (e.g., "1500,50")
        if (clean.matches(Regex(".*,\\d{2}$"))) {
            clean = clean.replace(".", "").replace(",", ".")
        } 
        // If there's a dot followed by exactly two digits at the end (e.g., "1500.50")
        else if (clean.matches(Regex(".*\\.\\d{2}$"))) {
            clean = clean.replace(",", "")
        }
        // Otherwise, remove all separators (treat as thousands)
        else {
            clean = clean.replace(".", "").replace(",", "")
        }
        
        return clean.toFloatOrNull()
    }

    // Performs math and updates overlay views
    private fun showOverlayForCalculatedValues(price: Float, distance: Float, distanceDetails: String) {
        // Load preferences
        val fuelType = sharedPreferences.getString("fuel_type", "nafta") ?: "nafta"
        val priceNafta = sharedPreferences.getFloat("price_nafta", 1100f)
        val priceGnc = sharedPreferences.getFloat("price_gnc", 500f)
        val consumption = sharedPreferences.getFloat("consumption", 10f)
        val thGreen = sharedPreferences.getFloat("threshold_green", 250f)
        val thRed = sharedPreferences.getFloat("threshold_red", 120f)

        // Mathematical model:
        val fuelPrice = if (fuelType == "gnc") priceGnc else priceNafta
        val fuelCostPerKm = fuelPrice / consumption
        val totalFuelCost = distance * fuelCostPerKm
        val netRatePerKm = (price / distance) - fuelCostPerKm
        val grossRatePerKm = price / distance

        // Render on UI thread
        handler.post {
            createOrUpdateOverlayView(price, distance, distanceDetails, netRatePerKm, totalFuelCost, grossRatePerKm, thGreen, thRed, fuelType)
        }
    }

    private fun createOrUpdateOverlayView(
        price: Float,
        distance: Float,
        distanceDetails: String,
        netRatePerKm: Float,
        totalFuelCost: Float,
        grossRatePerKm: Float,
        thGreen: Float,
        thRed: Float,
        fuelType: String
    ) {
        if (overlayView == null) {
            val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_UberNetCalculator)
            val inflater = LayoutInflater.from(contextThemeWrapper)
            overlayView = inflater.inflate(R.layout.layout_overlay, null)

            val lp = WindowManager.LayoutParams().apply {
                type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 150
            }

            // Close button listener
            overlayView?.findViewById<ImageButton>(R.id.btn_close_overlay)?.setOnClickListener {
                removeOverlay()
            }

            windowManager?.addView(overlayView, lp)
        }

        // Update Overlay UI Elements
        val tvOverlayTitle = overlayView?.findViewById<TextView>(R.id.tv_overlay_title)
        val tvNetGainVal = overlayView?.findViewById<TextView>(R.id.tv_net_gain_val)
        val tvProfitabilityTag = overlayView?.findViewById<TextView>(R.id.tv_profitability_tag)
        val tvFuelCostVal = overlayView?.findViewById<TextView>(R.id.tv_fuel_cost_val)
        val tvGrossGainVal = overlayView?.findViewById<TextView>(R.id.tv_gross_gain_val)

        // Set Values
        tvOverlayTitle?.text = "Res. Neto • $distanceDetails"
        tvNetGainVal?.text = String.format(Locale.US, "+$%.2f / km", netRatePerKm)
        tvFuelCostVal?.text = String.format(Locale.US, "-$%.2f", totalFuelCost)
        tvGrossGainVal?.text = String.format(Locale.US, "$%.2f/km", grossRatePerKm)

        // Set profitability color indicators and tags
        if (tvProfitabilityTag != null) {
            when {
                netRatePerKm >= thGreen -> {
                    tvProfitabilityTag.text = "RENTABILIDAD ALTA (${fuelType.uppercase()})"
                    tvProfitabilityTag.setBackgroundColor(getColor(R.color.profit_high))
                    tvProfitabilityTag.setTextColor(getColor(R.color.white))
                }
                netRatePerKm < thRed -> {
                    tvProfitabilityTag.text = "RENTABILIDAD BAJA (${fuelType.uppercase()})"
                    tvProfitabilityTag.setBackgroundColor(getColor(R.color.profit_low))
                    tvProfitabilityTag.setTextColor(getColor(R.color.white))
                }
                else -> {
                    tvProfitabilityTag.text = "RENTABILIDAD MEDIA (${fuelType.uppercase()})"
                    tvProfitabilityTag.setBackgroundColor(getColor(R.color.profit_medium))
                    tvProfitabilityTag.setTextColor(getColor(R.color.black))
                }
            }
        }

        // Schedule auto-dismiss in 15 seconds
        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, 15000)
    }

    private fun removeOverlay() {
        if (overlayView != null) {
            try {
                windowManager?.removeView(overlayView)
            } catch (e: Exception) {
                Log.e(tag, "Error removing overlay view: ${e.message}")
            }
            overlayView = null
        }
    }
}
