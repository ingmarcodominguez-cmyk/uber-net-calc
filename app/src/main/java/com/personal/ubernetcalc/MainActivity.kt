package com.personal.ubernetcalc

import android.net.Uri
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.personal.ubernetcalc.databinding.ActivityMainBinding

import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        sharedPreferences = getSharedPreferences("UberNetCalcPrefs", Context.MODE_PRIVATE)

        loadSettings()
        checkOverlayPermission()

        binding.btnSave.setOnClickListener {
            saveSettings()
        }

        binding.btnEnableService.setOnClickListener {
            // Open Android Accessibility settings
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "Busca 'Calculador Uber Net' y actívalo", Toast.LENGTH_LONG).show()
        }

        binding.btnSimulate.setOnClickListener {
            simulateTrip()
        }

        binding.btnRefreshLog.setOnClickListener {
            try {
                val file = File(cacheDir, "last_uber_screen.txt")
                val logText = if (file.exists()) {
                    file.readText()
                } else {
                    "No hay logs guardados aún. Espera a que la app de Uber envíe eventos de accesibilidad."
                }
                binding.tvLastScannedText.text = logText
            } catch (e: Exception) {
                binding.tvLastScannedText.text = "Error al leer log: ${e.message}"
            }
        }

        binding.btnClearLog.setOnClickListener {
            try {
                val file = File(cacheDir, "last_uber_screen.txt")
                if (file.exists()) {
                    file.delete()
                }
                binding.tvLastScannedText.text = "Historial limpio. Esperando nuevos eventos de Uber..."
                Toast.makeText(this, "Consola limpiada", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                binding.tvLastScannedText.text = "Error al limpiar log: ${e.message}"
            }
        }
    }

    private fun loadSettings() {
        val fuelType = sharedPreferences.getString("fuel_type", "nafta")
        if (fuelType == "gnc") {
            binding.rbGnc.isChecked = true
        } else {
            binding.rbNafta.isChecked = true
        }

        val priceNafta = sharedPreferences.getFloat("price_nafta", 1100f)
        val priceGnc = sharedPreferences.getFloat("price_gnc", 500f)
        val consumption = sharedPreferences.getFloat("consumption", 10f)
        val thGreen = sharedPreferences.getFloat("threshold_green", 250f)
        val thRed = sharedPreferences.getFloat("threshold_red", 120f)

        binding.etPriceNafta.setText(priceNafta.toString())
        binding.etPriceGnc.setText(priceGnc.toString())
        binding.etConsumption.setText(consumption.toString())
        binding.etThresholdGreen.setText(thGreen.toString())
        binding.etThresholdRed.setText(thRed.toString())
    }

    private fun saveSettings() {
        val selectedFuel = if (binding.rbGnc.isChecked) "gnc" else "nafta"
        val priceNafta = binding.etPriceNafta.text.toString().toFloatOrNull() ?: 1100f
        val priceGnc = binding.etPriceGnc.text.toString().toFloatOrNull() ?: 500f
        val consumption = binding.etConsumption.text.toString().toFloatOrNull() ?: 10f
        val thGreen = binding.etThresholdGreen.text.toString().toFloatOrNull() ?: 250f
        val thRed = binding.etThresholdRed.text.toString().toFloatOrNull() ?: 120f

        sharedPreferences.edit().apply {
            putString("fuel_type", selectedFuel)
            putFloat("price_nafta", priceNafta)
            putFloat("price_gnc", priceGnc)
            putFloat("consumption", consumption)
            putFloat("threshold_green", thGreen)
            putFloat("threshold_red", thRed)
            apply()
        }

        Toast.makeText(this, getString(R.string.settings_saved), Toast.LENGTH_SHORT).show()
    }

    private fun checkOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            Toast.makeText(
                this,
                "Por favor, activa el permiso 'Mostrar sobre otras apps' para que la burbuja funcione",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun simulateTrip() {
        val price = binding.etSimPrice.text.toString().toFloatOrNull() ?: 3500f
        val distance = binding.etSimDist.text.toString().toFloatOrNull() ?: 8.5f

        // Send broadcast to UberAccessibilityService
        val intent = Intent("com.personal.ubernetcalc.SIMULATE_TRIP")
        intent.setPackage(packageName)
        intent.putExtra("price", price)
        intent.putExtra("distance", distance)
        sendBroadcast(intent)
        
        Toast.makeText(this, "Simulación enviada. Si el servicio de accesibilidad está activo, verás la burbuja flotante.", Toast.LENGTH_LONG).show()
    }
}
