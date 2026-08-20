package com.personal.ubernetcalc

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
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
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.File
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
    private var userDismissedLastOffer = false

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
        ContextCompat.registerReceiver(this, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.d(tag, "Accessibility Service Connected")
        
        // Dynamically configure Accessibility Service to override potential XML bugs on Xiaomi/Samsung
        try {
            val info = serviceInfo ?: AccessibilityServiceInfo()
            info.eventTypes = AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                              AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                              AccessibilityEvent.TYPE_WINDOWS_CHANGED
            info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
            info.flags = AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                         AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                         info.flags // Keep existing flags from XML
            info.notificationTimeout = 100
            info.packageNames = arrayOf("com.ubercab.driver", "android", "com.android.systemui")
            serviceInfo = info
            Log.d(tag, "Accessibility Service configured dynamically in onServiceConnected")
        } catch (e: Exception) {
            Log.e(tag, "Error setting dynamic service info: ${e.message}")
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        
        // Ignore events originating from our own application to prevent infinite loops and UI lag
        val eventPkg = event.packageName?.toString() ?: ""
        if (eventPkg == packageName) return
        
        // Save a heartbeat log of the event so we know the service is alive and receiving events from other apps
        val eventTypeStr = try {
            AccessibilityEvent.eventTypeToString(event.eventType)
        } catch (e: Exception) {
            event.eventType.toString()
        }
        saveLogToFile("Activo: Evento de $eventPkg | Tipo: $eventTypeStr")
        
        // Log all raw text from Uber to diagnose screen reading
        if (eventPkg == "com.ubercab.driver") {
            val rawTexts = mutableSetOf<String>()
            rootInActiveWindow?.let { root ->
                traverseNode(root, rawTexts)
                root.recycle()
            }
            event.source?.let { source ->
                traverseNode(source, rawTexts)
                source.recycle()
            }
            val allWindows = windows
            val windowPkgs = allWindows?.map { "${it.packageName} (type=${it.type})" } ?: emptyList()
            val eventTexts = event.text?.mapNotNull { it?.toString() } ?: emptyList()
            saveLogToFile("Uber Contenido: ${rawTexts.toList()} | Windows: $windowPkgs | EventText: $eventTexts")
        }
        
        // Scan for screen changes
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            
            var foundOffer: Pair<Float, Float>? = null
            
            // 1. Try to find the offer in the active window root using card container search
            rootInActiveWindow?.let { root ->
                foundOffer = findOfferInContainer(root)
                root.recycle()
            }
            
            // 2. Try event source if not found yet
            if (foundOffer == null) {
                event.source?.let { source ->
                    // Walk up to get root of source window safely
                    var current: AccessibilityNodeInfo? = AccessibilityNodeInfo.obtain(source)
                    while (current?.parent != null) {
                        val parent = current.parent
                        current.recycle()
                        current = parent
                    }
                    current?.let { root ->
                        foundOffer = findOfferInContainer(root)
                        root.recycle()
                    }
                    source.recycle()
                }
            }
            
            // 3. Try other windows if not found yet
            if (foundOffer == null) {
                val allWindows = windows
                if (!allWindows.isNullOrEmpty()) {
                    for (window in allWindows) {
                        if (foundOffer != null) break
                        window.root?.let { root ->
                            foundOffer = findOfferInContainer(root)
                            root.recycle()
                        }
                    }
                }
            }

            // If we found a valid card containing both price and distance, trigger overlay
            if (foundOffer != null) {
                val (price, distance) = foundOffer!!
                val distanceDetails = String.format(Locale.US, "%.1f km", distance)
                
                val logLine = "OFERTA ENCONTRADA EN TARJETA: Price=$price, Dist=$distance"
                saveLogToFile(logLine)
                
                val now = System.currentTimeMillis()
                val timeDiff = now - lastCalculationTime
                
                if (price != lastPrice || distance != lastDistance) {
                    lastPrice = price
                    lastDistance = distance
                    userDismissedLastOffer = false
                    lastCalculationTime = now
                    
                    handler.post {
                        Toast.makeText(this, "Lector (Tarjeta): Precio $price | Dist $distance km", Toast.LENGTH_SHORT).show()
                    }
                    showOverlayForCalculatedValues(price, distance, distanceDetails)
                } else if (!userDismissedLastOffer && timeDiff > 8000) {
                    lastCalculationTime = now
                    showOverlayForCalculatedValues(price, distance, distanceDetails)
                }
            } else {
                // Fallback: run the old flat text scan to keep broad compatibility (scanning all windows)
                val texts = mutableSetOf<String>()
                rootInActiveWindow?.let { root ->
                    traverseNode(root, texts)
                    root.recycle()
                }
                event.source?.let { source ->
                    traverseNode(source, texts)
                    source.recycle()
                }
                val allWindows = windows
                if (!allWindows.isNullOrEmpty()) {
                    for (window in allWindows) {
                        window.root?.let { root ->
                            traverseNode(root, texts)
                            root.recycle()
                        }
                    }
                }
                
                if (texts.isNotEmpty()) {
                    parseAndProcessTexts(texts.toList(), eventPkg)
                }
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

    // Helper: Find all nodes that contain a distance tag
    private fun findDistanceNodes(node: AccessibilityNodeInfo?, list: MutableList<AccessibilityNodeInfo>) {
        if (node == null) return
        
        // Skip nodes belonging to our own application to avoid self-feedback loop
        if (node.packageName?.toString() == packageName) return
        
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (text.contains("km", ignoreCase = true) || text.contains("kms", ignoreCase = true) || text.contains("kilómetros", ignoreCase = true)) {
            list.add(AccessibilityNodeInfo.obtain(node))
        }
        
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            findDistanceNodes(child, list)
            child?.recycle()
        }
    }

    // Helper: Traverse container nodes for texts
    private fun traverseNodeSimple(node: AccessibilityNodeInfo?, texts: MutableList<String>) {
        if (node == null) return
        
        // Skip nodes belonging to our own application
        if (node.packageName?.toString() == packageName) return
        
        val text = node.text?.toString() ?: node.contentDescription?.toString() ?: ""
        if (text.isNotEmpty()) {
            texts.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseNodeSimple(child, texts)
            child?.recycle()
        }
    }

    private fun recycleNodeList(list: List<AccessibilityNodeInfo>) {
        for (node in list) {
            node.recycle()
        }
    }

    // Smart contextual search: Finds a container holding both price and distance
    private fun findOfferInContainer(root: AccessibilityNodeInfo): Pair<Float, Float>? {
        val distanceNodes = mutableListOf<AccessibilityNodeInfo>()
        findDistanceNodes(root, distanceNodes)
        
        val distancePattern = Pattern.compile("([\\d,\\.]+)[\\s\\u00A0\\u202F]*(?:km|kms|kil\\u00f3metros|kilometros)", Pattern.CASE_INSENSITIVE)
        val pricePattern = Pattern.compile("(?:ARS|AR\\$|\\$)[\\s\\u00A0\\u202F]*([\\d\\.,]+)", Pattern.CASE_INSENSITIVE)

        for (distNode in distanceNodes) {
            // Walk up to 8 levels of parents safely (needed for deep Compose layouts)
            var ancestor = AccessibilityNodeInfo.obtain(distNode)
            for (level in 0..8) {
                val parent = ancestor.parent
                if (parent != null) {
                    val containerTexts = mutableListOf<String>()
                    traverseNodeSimple(parent, containerTexts)
                    
                    val combinedText = containerTexts.joinToString(" ")
                    
                    var foundDist: Float? = null
                    val distMatcher = distancePattern.matcher(combinedText)
                    if (distMatcher.find()) {
                        foundDist = distMatcher.group(1)?.replace(",", ".")?.toFloatOrNull()
                    }
                    
                    var foundPrice: Float? = null
                    val priceMatcher = pricePattern.matcher(combinedText)
                    while (priceMatcher.find()) {
                        val rawPrice = priceMatcher.group(0) ?: ""
                        val parsed = cleanPrice(rawPrice)
                        if (parsed != null && (foundPrice == null || parsed > foundPrice)) {
                            foundPrice = parsed
                        }
                    }
                    
                    if (foundDist != null && foundPrice != null) {
                        parent.recycle()
                        ancestor.recycle()
                        recycleNodeList(distanceNodes)
                        return Pair(foundPrice, foundDist)
                    }
                    
                    val prevAncestor = ancestor
                    ancestor = parent
                    prevAncestor.recycle()
                } else {
                    break
                }
            }
            ancestor.recycle()
        }
        
        recycleNodeList(distanceNodes)
        return null
    }

    // Recursively extracts all texts and content descriptions visible on the screen
    private fun traverseNode(node: AccessibilityNodeInfo?, texts: MutableCollection<String>) {
        if (node == null) return
        
        // Skip nodes belonging to our own application
        if (node.packageName?.toString() == packageName) return
        
        val nodeText = node.text
        if (nodeText != null && nodeText.isNotEmpty()) {
            texts.add(nodeText.toString())
        }
        
        val contentDesc = node.contentDescription
        if (contentDesc != null && contentDesc.isNotEmpty() && contentDesc.toString() != nodeText?.toString()) {
            texts.add(contentDesc.toString())
        }

        for (i in 0 until node.childCount) {
            val child = node.getChild(i)
            traverseNode(child, texts)
            child?.recycle()
        }
    }

    private fun saveLogToFile(text: String) {
        try {
            val file = File(cacheDir, "last_uber_screen.txt")
            val existingText = if (file.exists()) file.readText() else ""
            val timestamp = java.text.SimpleDateFormat("HH:mm:ss", Locale.US).format(java.util.Date())
            val newLog = "[$timestamp] $text"
            
            val lines = existingText.split("\n").filter { it.isNotEmpty() }.toMutableList()
            lines.add(0, newLog)
            
            // Mantener solo los últimos 30 registros
            if (lines.size > 30) {
                file.writeText(lines.subList(0, 30).joinToString("\n"))
            } else {
                file.writeText(lines.joinToString("\n"))
            }
            
            lastScannedText = file.readText()
        } catch (e: Exception) {
            Log.e(tag, "Error saving log file: ${e.message}")
        }
    }

    // Main text parser for Uber screen contents (Fallback)
    private fun parseAndProcessTexts(texts: List<String>, eventPackage: String?) {
        val combinedText = texts.joinToString(" ")
        
        var foundPrice: Float? = null
        val foundDistances = mutableListOf<Float>()

        // Patterns to match ARS prices and distances from the Uber Driver screen
        val pricePattern = Pattern.compile("(?:ARS|AR\\$|\\$)[\\s\\u00A0\\u202F]*([\\d\\.,]+)", Pattern.CASE_INSENSITIVE)
        val distancePattern = Pattern.compile("([\\d,\\.]+)[\\s\\u00A0\\u202F]*(?:km|kms|kil\\u00f3metros|kilometros)", Pattern.CASE_INSENSITIVE)

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

        // --- DIAGNOSTIC TOASTS ---
        handler.post {
            if (foundPrice != null || foundDistances.isNotEmpty()) {
                val priceMsg = if (foundPrice != null) "Precio: $foundPrice" else "Precio: No detectado"
                val distMsg = if (foundDistances.isNotEmpty()) "Dist: $foundDistances" else "Dist: No detectada"
                Log.d(tag, "Diag: $priceMsg | $distMsg")
                Toast.makeText(this, "Lector (Fallback): $priceMsg | $distMsg", Toast.LENGTH_SHORT).show()
            }
        }

        if (foundPrice != null && foundDistances.isNotEmpty()) {
            val totalDistance: Float
            val distanceDetails: String

            if (foundDistances.size >= 2) {
                val pickupDist = foundDistances[0]
                val tripDist = foundDistances[1]
                totalDistance = pickupDist + tripDist
                distanceDetails = String.format(Locale.US, "%.1f km (%.1f + %.1f)", totalDistance, pickupDist, tripDist)
            } else {
                totalDistance = foundDistances[0]
                distanceDetails = String.format(Locale.US, "%.1f km", totalDistance)
            }

            if (totalDistance > 0.05f) {
                val now = System.currentTimeMillis()
                val timeDiff = now - lastCalculationTime
                if (foundPrice != lastPrice || totalDistance != lastDistance) {
                    lastPrice = foundPrice
                    lastDistance = totalDistance
                    userDismissedLastOffer = false
                    lastCalculationTime = now
                    
                    val logLine = "Detected Uber offer (Fallback): Price=$foundPrice, TotalDistance=$totalDistance ($distanceDetails)"
                    saveLogToFile(logLine)
                    showOverlayForCalculatedValues(foundPrice, totalDistance, distanceDetails)
                } else if (!userDismissedLastOffer && timeDiff > 8000) {
                    lastCalculationTime = now
                    showOverlayForCalculatedValues(foundPrice, totalDistance, distanceDetails)
                }
            }
        }
    }

    private fun cleanPrice(rawPrice: String): Float? {
        var clean = rawPrice.replace("ARS", "", true)
                            .replace("AR$", "", true)
                            .replace("$", "")
                            .trim()
        
        if (clean.matches(Regex(".*,\\d{2}$"))) {
            clean = clean.replace(".", "").replace(",", ".")
        } 
        else if (clean.matches(Regex(".*\\.\\d{2}$"))) {
            clean = clean.replace(",", "")
        }
        else {
            clean = clean.replace(".", "").replace(",", "")
        }
        
        return clean.toFloatOrNull()
    }

    // Performs math and updates overlay views
    private fun showOverlayForCalculatedValues(price: Float, distance: Float, distanceDetails: String) {
        val fuelType = sharedPreferences.getString("fuel_type", "nafta") ?: "nafta"
        val priceNafta = sharedPreferences.getFloat("price_nafta", 1100f)
        val priceGnc = sharedPreferences.getFloat("price_gnc", 500f)
        val consumption = sharedPreferences.getFloat("consumption", 10f)
        val thGreen = sharedPreferences.getFloat("threshold_green", 250f)
        val thRed = sharedPreferences.getFloat("threshold_red", 120f)

        val fuelPrice = if (fuelType == "gnc") priceGnc else priceNafta
        val fuelCostPerKm = fuelPrice / consumption
        val totalFuelCost = distance * fuelCostPerKm
        val netRatePerKm = (price / distance) - fuelCostPerKm
        val grossRatePerKm = price / distance

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
                type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                format = PixelFormat.TRANSLUCENT
                flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                width = WindowManager.LayoutParams.WRAP_CONTENT
                height = WindowManager.LayoutParams.WRAP_CONTENT
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = 150
            }

            overlayView?.findViewById<ImageButton>(R.id.btn_close_overlay)?.setOnClickListener {
                userDismissedLastOffer = true
                removeOverlay()
            }

            windowManager?.addView(overlayView, lp)
        }

        val tvOverlayTitle = overlayView?.findViewById<TextView>(R.id.tv_overlay_title)
        val tvNetGainVal = overlayView?.findViewById<TextView>(R.id.tv_net_gain_val)
        val tvProfitabilityTag = overlayView?.findViewById<TextView>(R.id.tv_profitability_tag)
        val tvFuelCostVal = overlayView?.findViewById<TextView>(R.id.tv_fuel_cost_val)
        val tvGrossGainVal = overlayView?.findViewById<TextView>(R.id.tv_gross_gain_val)

        tvOverlayTitle?.text = "Res. Neto • $distanceDetails"
        tvNetGainVal?.text = String.format(Locale.US, "+$%.2f / km", netRatePerKm)
        tvFuelCostVal?.text = String.format(Locale.US, "-$%.2f", totalFuelCost)
        tvGrossGainVal?.text = String.format(Locale.US, "$%.2f/km", grossRatePerKm)

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

        handler.removeCallbacks(dismissRunnable)
        handler.postDelayed(dismissRunnable, 45000) // 45 segundos para que te dé tiempo a leerlo manejando
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

    companion object {
        var lastScannedText: String = "No se ha escaneado ninguna pantalla aún."
    }
}
 
