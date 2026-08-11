package me.rerere.ai.provider

import kotlin.uuid.Uuid

const val NVIDIA_NIM_DEFAULT_BASE_URL = "https://integrate.api.nvidia.com/v1"
const val HUGGING_FACE_DEFAULT_BASE_URL = "https://router.huggingface.co/v1"

fun openAICompatiblePreset(
    preset: OpenAICompatiblePreset,
    id: Uuid = Uuid.random(),
): ProviderSetting.OpenAI = when (preset) {
    OpenAICompatiblePreset.OPENAI -> ProviderSetting.OpenAI(
        id = id,
        name = "OpenAI",
        baseUrl = "https://api.openai.com/v1",
        allowEmptyApiKey = false,
        compatibilityPreset = preset,
        compatibilityCapabilities = OpenAICompatibilityCapabilities(responsesApi = true),
    )
    OpenAICompatiblePreset.NVIDIA -> ProviderSetting.OpenAI(
        id = id,
        name = "NVIDIA NIM",
        baseUrl = NVIDIA_NIM_DEFAULT_BASE_URL,
        compatibilityPreset = preset,
        compatibilityCapabilities = OpenAICompatibilityCapabilities(
            responsesApi = false,
            embeddings = false,
            imageGeneration = false,
        ),
    )
    OpenAICompatiblePreset.HUGGING_FACE -> ProviderSetting.OpenAI(
        id = id,
        name = "Hugging Face",
        baseUrl = HUGGING_FACE_DEFAULT_BASE_URL,
        allowEmptyApiKey = false,
        compatibilityPreset = preset,
        compatibilityCapabilities = OpenAICompatibilityCapabilities(
            responsesApi = false,
            embeddings = false,
            imageGeneration = false,
        ),
    )
    OpenAICompatiblePreset.CUSTOM -> ProviderSetting.OpenAI(
        id = id,
        name = "OpenAI Compatible",
        baseUrl = "",
        compatibilityPreset = preset,
        compatibilityCapabilities = OpenAICompatibilityCapabilities(
            responsesApi = false,
            embeddings = false,
            imageGeneration = false,
        ),
    )
}
