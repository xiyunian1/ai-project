const DEFAULT_API_BASE = 'http://localhost:8081'

export const normalizeApiBase = (base = DEFAULT_API_BASE) => {
  const value = String(base || DEFAULT_API_BASE).trim()
  return value.replace(/\/+$/, '') || DEFAULT_API_BASE
}

export const API_BASE = normalizeApiBase(import.meta.env?.VITE_API_BASE_URL || DEFAULT_API_BASE)

export const buildApiUrl = (path, params, base = API_BASE) => {
  const normalizedBase = normalizeApiBase(base)
  const normalizedPath = String(path || '').replace(/^\/+/, '')
  const url = `${normalizedBase}/${normalizedPath}`
  const query = new URLSearchParams()

  Object.entries(params || {}).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') return
    query.append(key, String(value))
  })

  const queryString = query.toString()
  return queryString ? `${url}?${queryString}` : url
}

export const parseJsonSafe = async (response) => {
  try {
    return await response.json()
  } catch {
    return null
  }
}
