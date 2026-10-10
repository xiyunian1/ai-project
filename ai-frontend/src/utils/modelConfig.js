export const MODEL_CONFIG_STORAGE_KEY = 'WMS_MODEL_CONFIG'

export const createDefaultModelConfig = () => ({
  provider: 'openai-compatible',
  baseUrl: 'https://api.deepseek.com',
  apiKey: '',
  model: ''
})

export const cloneModelConfig = (source) => ({
  provider: source?.provider || 'openai-compatible',
  baseUrl: source?.baseUrl || source?.deepseekBaseUrl || 'https://api.deepseek.com',
  apiKey: source?.apiKey || source?.deepseekApiKey || '',
  model: source?.model || source?.deepseekModel || ''
})

export const toStoredModelConfig = (source) => cloneModelConfig(source)

export const loadModelConfigFromSession = (storage) => {
  const raw = storage.getItem(MODEL_CONFIG_STORAGE_KEY)
  if (!raw) return createDefaultModelConfig()

  try {
    return cloneModelConfig(JSON.parse(raw))
  } catch {
    return createDefaultModelConfig()
  }
}

export const saveModelConfigToSession = (storage, source) => {
  storage.setItem(MODEL_CONFIG_STORAGE_KEY, JSON.stringify(toStoredModelConfig(source)))
}

export const clearModelConfigFromSession = (storage) => {
  storage.removeItem(MODEL_CONFIG_STORAGE_KEY)
}

export const loadModelConfigFromLocal = (storage) => {
  const raw = storage.getItem(MODEL_CONFIG_STORAGE_KEY)
  if (!raw) return createDefaultModelConfig()

  try {
    return cloneModelConfig(JSON.parse(raw))
  } catch {
    return createDefaultModelConfig()
  }
}

export const saveModelConfigToLocal = (storage, source) => {
  storage.setItem(MODEL_CONFIG_STORAGE_KEY, JSON.stringify(toStoredModelConfig(source)))
}

export const clearModelConfigFromLocal = (storage) => {
  storage.removeItem(MODEL_CONFIG_STORAGE_KEY)
}

export const buildModelConfigPayload = (source) => {
  const cfg = cloneModelConfig(source)

  return {
    enabled: true,
    provider: cfg.provider.trim(),
    baseUrl: cfg.baseUrl.trim(),
    apiKey: cfg.apiKey,
    model: cfg.model.trim()
  }
}
