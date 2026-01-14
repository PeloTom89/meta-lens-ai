package com.metalens.app.pictureanalysis

data class PictureAnalysisUiState(
    val isAnalyzing: Boolean = false,
    val resultText: String? = null,
    val recentError: String? = null,
) {
    val hasResult: Boolean = !resultText.isNullOrBlank()
}

