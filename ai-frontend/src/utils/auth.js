export const TOKEN_KEY = 'USER_TOKEN'
export const TOKEN_EXPIRES_AT_KEY = 'USER_TOKEN_EXPIRES_AT'
export const NAME_KEY = 'USER_NAME'
export const ACCOUNT_KEY = 'USER_ACCOUNT'

export const getToken = () => localStorage.getItem(TOKEN_KEY) || ''

export const isTokenValid = () => {
  const token = getToken()
  if (!token) return false

  const expiresAtRaw = localStorage.getItem(TOKEN_EXPIRES_AT_KEY)
  if (!expiresAtRaw) return true

  const expiresAt = Number(expiresAtRaw)
  if (Number.isNaN(expiresAt)) return false

  return Date.now() < (expiresAt - 5000)
}

export const setAuthSession = ({ token, expiresAt, username, account }) => {
  localStorage.setItem(TOKEN_KEY, token)
  if (expiresAt) {
    localStorage.setItem(TOKEN_EXPIRES_AT_KEY, String(expiresAt))
  } else {
    localStorage.removeItem(TOKEN_EXPIRES_AT_KEY)
  }
  if (username) localStorage.setItem(NAME_KEY, username)
  if (account) localStorage.setItem(ACCOUNT_KEY, account)
}

export const clearAuthSession = () => {
  localStorage.removeItem(TOKEN_KEY)
  localStorage.removeItem(TOKEN_EXPIRES_AT_KEY)
  localStorage.removeItem(NAME_KEY)
  localStorage.removeItem(ACCOUNT_KEY)
}

export const withAuthHeaders = (headers = {}) => {
  const token = getToken()
  if (!token) return headers
  return {
    ...headers,
    Authorization: `Bearer ${token}`
  }
}
