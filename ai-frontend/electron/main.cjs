const { app, BrowserWindow, dialog } = require('electron')
const path = require('path')
const log = require('electron-log')
const { autoUpdater } = require('electron-updater')

const isDev = !app.isPackaged

function createWindow() {
  const mainWindow = new BrowserWindow({
    width: 1200,
    height: 800,
    minWidth: 960,
    minHeight: 640,
    webPreferences: {
      preload: path.join(__dirname, 'preload.cjs'),
      nodeIntegration: false,
      contextIsolation: true,
      sandbox: true,
      webSecurity: true
    }
  })

  mainWindow.webContents.setWindowOpenHandler(() => ({ action: 'deny' }))

  if (isDev) {
    const devServerUrl = process.env.ELECTRON_RENDERER_URL || 'http://localhost:5173/'
    mainWindow.loadURL(devServerUrl).catch((error) => {
      log.error('Failed to load dev server:', error)
    })
  } else {
    const indexPath = path.join(__dirname, '../dist/index.html')
    mainWindow.loadFile(indexPath).catch((error) => {
      log.error('Failed to load built file:', error)
      dialog.showErrorBox('加载失败', '无法加载桌面应用页面，请重新安装应用。')
    })
  }
}

function configureAutoUpdater() {
  if (isDev) return

  const updateUrl = process.env.APP_UPDATE_URL || ''
  if (!updateUrl.startsWith('https://')) {
    log.warn('Skip auto update: APP_UPDATE_URL must be HTTPS.')
    return
  }

  autoUpdater.logger = log
  autoUpdater.logger.transports.file.level = 'info'
  autoUpdater.setFeedURL({ provider: 'generic', url: updateUrl })
  autoUpdater.checkForUpdatesAndNotify()

  autoUpdater.on('update-downloaded', () => {
    dialog.showMessageBox({
      type: 'info',
      title: '更新已就绪',
      message: '检测到新版本，是否立即重启安装？',
      buttons: ['立即重启', '稍后']
    }).then(({ response }) => {
      if (response === 0) {
        autoUpdater.quitAndInstall()
      }
    })
  })
}

app.whenReady().then(() => {
  createWindow()
  configureAutoUpdater()

  app.on('activate', () => {
    if (BrowserWindow.getAllWindows().length === 0) {
      createWindow()
    }
  })
})

app.on('window-all-closed', () => {
  if (process.platform !== 'darwin') {
    app.quit()
  }
})

process.on('uncaughtException', (error) => {
  log.error('Uncaught exception:', error)
})
