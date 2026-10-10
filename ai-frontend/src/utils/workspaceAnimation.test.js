import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { dirname, resolve } from 'node:path'

const currentDir = dirname(fileURLToPath(import.meta.url))
const themeCss = readFileSync(resolve(currentDir, '../styles/theme.css'), 'utf8')

test('workspace navigation does not replay global page entrance animation', () => {
  assert.equal(themeCss.includes('.workspace-sidebar,\n.workspace-main > *'), false)
  assert.equal(themeCss.includes('.workspace-main > *:nth-child'), false)
})

test('workspace theme uses stronger presentation hierarchy surfaces', () => {
  assert.match(themeCss, /--surface-panel-max-width:/)
  assert.match(themeCss, /\.workspace-main > \* \{\n  width: min\(100%, var\(--surface-panel-max-width\)\);/)
  assert.match(themeCss, /\.surface-panel\.is-hero/)
  assert.match(themeCss, /\.workspace-topbar\.is-hero/)
  assert.match(themeCss, /box-shadow: var\(--shadow-sm\), inset 0 1px 0 rgba\(255, 255, 255, 0\.32\);/)
})
