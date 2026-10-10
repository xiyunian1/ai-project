export const createDefaultDbConfig = () => ({
  enabled: true,
  server: 'localhost',
  port: 1433,
  database: '',
  username: '',
  password: '',
  jdbcUrl: '',
  allowedTables: '',
  discoverTables: true
})

export const cloneDbConfig = (source) => ({
  enabled: Boolean(source?.enabled),
  server: source?.server || 'localhost',
  port: Number(source?.port) || 1433,
  database: source?.database || '',
  username: source?.username || '',
  password: source?.password || '',
  jdbcUrl: source?.jdbcUrl || '',
  allowedTables: source?.allowedTables || '',
  discoverTables: source?.discoverTables !== false
})

export const toStoredDbConfig = (source) => ({
  ...cloneDbConfig(source),
  password: ''
})

export const fromStoredDbConfig = (source) => toStoredDbConfig(source)

export const parseStoredDbConfig = (raw) => {
  try {
    const parsed = JSON.parse(raw)
    const config = fromStoredDbConfig(parsed)
    return {
      config,
      shouldRewrite: JSON.stringify(parsed) !== JSON.stringify(config)
    }
  } catch {
    return {
      config: createDefaultDbConfig(),
      shouldRewrite: false
    }
  }
}

export const loadDbConfigFromStorage = (storage, key) => {
  const raw = storage.getItem(key)
  if (!raw) return createDefaultDbConfig()

  const loaded = parseStoredDbConfig(raw)
  if (loaded.shouldRewrite) {
    try {
      storage.setItem(key, JSON.stringify(toStoredDbConfig(loaded.config)))
    } catch {
      // Keep the sanitized in-memory config even if browser storage is blocked.
    }
  }
  return loaded.config
}

export const buildDbConfigPayload = (source) => {
  const cfg = cloneDbConfig(source)
  const server = cfg.server.trim()
  const database = cfg.database.trim()
  const jdbcUrl = cfg.jdbcUrl.trim()
  const enabled = Boolean(jdbcUrl || (server && database))

  return {
    enabled,
    server,
    port: Number(cfg.port) || 1433,
    database,
    username: cfg.username.trim(),
    password: cfg.password,
    jdbcUrl,
    allowedTables: cfg.allowedTables.trim(),
    discoverTables: cfg.discoverTables !== false
  }
}
