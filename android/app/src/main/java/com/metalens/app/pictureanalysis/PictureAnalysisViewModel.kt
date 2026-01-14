package com.metalens.app.pictureanalysis

import android.app.Application
import android.graphics.Bitmap
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.metalens.app.R
import com.metalens.app.settings.AppSettings
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class PictureAnalysisViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val service = OpenAIImageAnalysisService()

    private val _uiState = MutableStateFlow(PictureAnalysisUiState())
    val uiState: StateFlow<PictureAnalysisUiState> = _uiState.asStateFlow()

    private var job: Job? = null

    fun reset() {
        job?.cancel()
        job = null
        _uiState.value = PictureAnalysisUiState()
    }

    fun analyze(bitmap: Bitmap) {
        if (_uiState.value.isAnalyzing) return

        job?.cancel()
        job =
            viewModelScope.launch {
                _uiState.update { it.copy(isAnalyzing = true, recentError = null) }

                val ctx = getApplication<Application>().applicationContext
                val apiKey = AppSettings.getOpenAiApiKey(ctx).trim()

                // Settings model is currently tuned for Realtime. If it's a realtime model, map to a stable vision-capable model.
                val configuredModel = AppSettings.getOpenAiModel(ctx).trim()
                val model =
                    if (configuredModel.contains("realtime", ignoreCase = true)) {
                        "gpt-4o-mini"
                    } else {
                        configuredModel.ifBlank { "gpt-4o-mini" }
                    }

                val prompt = ctx.getString(R.string.ai_prompts).trim()

                val result =
                    withContext(Dispatchers.IO) {
                        service.analyzeImage(
                            apiKey = apiKey,
                            model = model,
                            prompt = prompt,
                            bitmap = bitmap,
                        )
                    }

                result.fold(
                    onSuccess = { text ->
                        _uiState.update { it.copy(isAnalyzing = false, resultText = text, recentError = null) }
                    },
                    onFailure = { err ->
                        _uiState.update {
                            it.copy(
                                isAnalyzing = false,
                                recentError =
                                    err.message
                                        ?: err.javaClass.simpleName
                                        ?: "Analysis failed",
                            )
                        }
                    },
                )
            }
    }
}

