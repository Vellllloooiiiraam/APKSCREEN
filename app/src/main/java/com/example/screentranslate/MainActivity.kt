package com.example.screentranslate

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.screentranslate.a11y.A11yTextSource
import com.example.screentranslate.capture.ScreenCaptureService
import com.example.screentranslate.core.Langs
import com.example.screentranslate.core.LogBus
import com.example.screentranslate.overlay.OverlayController
import com.example.screentranslate.pipeline.CaptureMode
import com.example.screentranslate.pipeline.TranslationPipeline
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private val projectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = result.data
        if (result.resultCode == Activity.RESULT_OK && data != null) {
            ScreenCaptureService.start(this, result.resultCode, data)
            LogBus.log("Main", "captura de tela autorizada")
        } else {
            LogBus.log("Main", "captura de tela negada pelo usuário")
        }
    }

    private val notificationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        LogBus.log("Main", "permissão de notificações: $granted")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusView = findViewById(R.id.statusView)
        logView = findViewById(R.id.logView)

        findViewById<Button>(R.id.btnOverlay).setOnClickListener {
            requestOverlayPermission()
        }
        findViewById<Button>(R.id.btnA11y).setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        findViewById<Button>(R.id.btnStartCapture).setOnClickListener {
            requestScreenCapture()
        }
        findViewById<Button>(R.id.btnStopCapture).setOnClickListener {
            ScreenCaptureService.stop(this)
            LogBus.log("Main", "captura de tela encerrada")
            updateStatus()
        }
        findViewById<Button>(R.id.btnBubble).setOnClickListener {
            OverlayController.toggle(this)
            statusView.postDelayed({ updateStatus() }, 300)
        }
        findViewById<Button>(R.id.btnTranslateNow).setOnClickListener {
            TranslationPipeline.requestOcrNow()
        }

        val autoSwitch = findViewById<Switch>(R.id.swAuto)
        autoSwitch.isChecked = TranslationPipeline.auto
        autoSwitch.setOnCheckedChangeListener { _, checked ->
            TranslationPipeline.setAuto(checked)
        }

        val modes = listOf(CaptureMode.HYBRID, CaptureMode.ACCESSIBILITY, CaptureMode.OCR)

        val modeSpinner = findViewById<Spinner>(R.id.spMode)
        modeSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            modes.map { TranslationPipeline.modeName(it) }
        )
        modeSpinner.setSelection(modes.indexOf(TranslationPipeline.state.value.mode).coerceAtLeast(0))
        modeSpinner.onItemSelectedListener = OnItemPicked { position ->
            TranslationPipeline.setMode(modes[position])
        }

        val targetSpinner = findViewById<Spinner>(R.id.spTarget)
        targetSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            Langs.targets.map { it.label }
        )
        targetSpinner.setSelection(Langs.indexOf(TranslationPipeline.target))
        targetSpinner.onItemSelectedListener = OnItemPicked { position ->
            TranslationPipeline.setTarget(Langs.targets[position].code)
        }

        requestNotificationPermission()
        observeLog()
        handleAutomationIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleAutomationIntent(intent)
    }

    /**
     * Gatilho de automacao por Intent, para ligar o overlay sem toque na tela.
     *
     * Existe por dois motivos concretos:
     *
     *  - emulador e CI: `adb shell am start` nao depende de coordenadas de tela, entao
     *    o teste nao quebra quando o layout muda;
     *  - alguns aparelhos (Xiaomi/HyperOS) bloqueiam `adb shell input tap` com
     *    `SecurityException: ... INJECT_EVENTS`, e sem isso nao haveria como ligar a
     *    traducao por ADB.
     *
     * Uso:
     *   adb shell am start -n com.example.screentranslate/.MainActivity \
     *       --ez auto true --ez overlay true
     */
    private fun handleAutomationIntent(intent: Intent?) {
        intent ?: return
        var touched = false

        if (intent.getBooleanExtra(EXTRA_AUTO, false)) {
            TranslationPipeline.setAuto(true)
            LogBus.log("Main", "automação: tradução automática ligada")
            touched = true
        }
        if (intent.getBooleanExtra(EXTRA_OVERLAY, false)) {
            TranslationPipeline.setAuto(true)
            OverlayController.show(this)
            LogBus.log("Main", "automação: overlay ligado")
            touched = true
        }
        if (intent.getBooleanExtra(EXTRA_CAPTURE, false)) {
            requestScreenCapture()
            touched = true
        }
        if (intent.getBooleanExtra(EXTRA_OCR, false)) {
            TranslationPipeline.requestOcrNow()
            touched = true
        }

        if (touched) {
            // O switch da tela precisa refletir o que a automacao ligou.
            findViewById<Switch>(R.id.swAuto)?.isChecked = TranslationPipeline.auto
            statusView.postDelayed({ updateStatus() }, 500)
        }
    }

    override fun onResume() {
        super.onResume()
        updateStatus()
    }

    // ------------------------------------------------------------------ acoes

    private fun requestOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            LogBus.log("Main", "permissão de sobreposição já concedida")
            return
        }
        LogBus.log("Main", "abrindo ajuste de sobreposição")
        startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
        )
    }

    private fun requestScreenCapture() {
        val manager = getSystemService(MediaProjectionManager::class.java)
        projectionLauncher.launch(manager.createScreenCaptureIntent())
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    // ------------------------------------------------------------------ estado

    private fun updateStatus() {
        val overlay = if (Settings.canDrawOverlays(this)) "OK" else "faltando"
        val a11y = if (A11yTextSource.connected) "OK" else "faltando"
        val capture = if (ScreenCaptureService.isRunning) "ativa" else "parada"
        val pipeline = TranslationPipeline.state.value
        val bubble = when {
            !OverlayController.isShown -> "oculta"
            OverlayController.anchorsVisible -> "ativa (${pipeline.blocks.size} blocos)"
            else -> "ligada, tradução pausada"
        }

        statusView.text = buildString {
            appendLine("Sobreposição: $overlay")
            appendLine("Acessibilidade: $a11y")
            appendLine("Captura de tela: $capture")
            append("Tradução na tela: $bubble")
        }
    }

    private fun observeLog() {
        lifecycleScope.launch {
            LogBus.lines.collect { lines ->
                logView.text = lines.takeLast(25).joinToString("\n")
                updateStatus()
            }
        }
    }

    private class OnItemPicked(private val onPicked: (Int) -> Unit) :
        AdapterView.OnItemSelectedListener {
        override fun onItemSelected(
            parent: AdapterView<*>?,
            view: View?,
            position: Int,
            id: Long
        ) = onPicked(position)

        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    companion object {
        // Extras do gatilho de automacao (ver handleAutomationIntent).
        const val EXTRA_AUTO = "auto"
        const val EXTRA_OVERLAY = "overlay"
        const val EXTRA_CAPTURE = "capture"
        const val EXTRA_OCR = "ocr"
    }
}