package com.example.screentranslate

import android.app.Application
import com.example.screentranslate.pipeline.TranslationPipeline

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        TranslationPipeline.init(this)
    }
}