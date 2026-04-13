export const THEME_STORAGE_KEY = 'WMS_THEME'

const resolvePreferredTheme = () => {
  if (typeof window === 'undefined') return 'light'

  const stored = window.localStorage.getItem(THEME_STORAGE_KEY)
  if (stored === 'light' || stored === 'dark') return stored

  if (window.matchMedia?.('(prefers-color-scheme: dark)').matches) {
    return 'dark'
  }
  return 'light'
}

export const applyTheme = (theme) => {
  if (typeof document === 'undefined' || typeof window === 'undefined') return theme

  const value = theme === 'dark' ? 'dark' : 'light'
  document.documentElement.setAttribute('data-theme', value)
  window.localStorage.setItem(THEME_STORAGE_KEY, value)
  return value
}

export const initTheme = () => applyTheme(resolvePreferredTheme())

export const getCurrentTheme = () => {
  if (typeof document === 'undefined') return 'light'
  return document.documentElement.getAttribute('data-theme') === 'dark' ? 'dark' : 'light'
}

export const toggleTheme = () => applyTheme(getCurrentTheme() === 'dark' ? 'light' : 'dark')
