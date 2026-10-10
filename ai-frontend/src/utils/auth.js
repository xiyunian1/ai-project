export const TOKEN_KEY = 'USER_TOKEN'
export const TOKEN_EXPIRES_AT_KEY = 'USER_TOKEN_EXPIRES_AT'
export const NAME_KEY = 'USER_NAME'
export const ACCOUNT_KEY = 'USER_ACCOUNT'
export const ROLE_KEY = 'USER_ROLE'

export const getToken = () => localStorage.getItem(TOKEN_KEY) || ''

export const decodeAccountFromToken = (token) => {
  try {
    const parts = String(token || '').split('.')
    if (parts.length !== 3) return ''
    const payload = parts[1].replace(/-/g, '+').replace(/_/g, '/')
    const paddedPayload = payload.padEnd(Math.ceil(payload.length / 4) * 4, '=')
    const bytes = Uint8Array.from(atob(paddedPayload), (ch) => ch.charCodeAt(0))
    const subject = JSON.parse(new TextDecoder('utf-8', { fatal: true }).decode(bytes))?.sub
    return typeof subject === 'string' ? subject : ''
  } catch {
    return ''
  }
}

export const resolveAccountFromToken = () => decodeAccountFromToken(getToken())

export const resolveAccountKey = ({ account, token, name } = {}) => {
  const rawKey = account || decodeAccountFromToken(token) || name || 'guest'
  const key = String(rawKey || '').trim()
  return key || 'guest'
}

export const getCurrentRole = () => localStorage.getItem(ROLE_KEY) || 'USER'

export const isAdminRole = (role = getCurrentRole()) => String(role || '').toUpperCase() === 'ADMIN'

export const isTokenValid = () => {
  const token = getToken()
  if (!token) return false

  const expiresAtRaw = localStorage.getItem(TOKEN_EXPIRES_AT_KEY)
  if (!expiresAtRaw) return true

  const expiresAt = Number(expiresAtRaw)
  if (Number.isNaN(expiresAt)) return false

  return Date.now() < (expiresAt - 5000)
}

export const setAuthSession = ({ token, expiresAt, username, account, role }) => {
  localStorage.setItem(TOKEN_KEY, token)
  if (expiresAt) {
    localStorage.setItem(TOKEN_EXPIRES_AT_KEY, String(expiresAt))
  } else {
    localStorage.removeItem(TOKEN_EXPIRES_AT_KEY)
  }
  if (username) localStorage.setItem(NAME_KEY, username)
  if (account) localStorage.setItem(ACCOUNT_KEY, account)
  localStorage.setItem(ROLE_KEY, role || 'USER')
}

export const clearAuthSession = () => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(TOKEN_EXPIRES_AT_KEY)
  localStorage.removeItem(NAME_KEY)
  localStorage.removeItem(ACCOUNT_KEY)
  localStorage.removeItem(ROLE_KEY)
}

export const withAuthHeaders = (headers = {}) => {
  const token = getToken()
  if (!token) return headers
  return {
    ...headers,
    Authorization: `Bearer ${token}`
  }
}
