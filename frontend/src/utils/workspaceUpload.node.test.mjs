import { createRequire } from 'module'
import test from 'node:test'
import assert from 'node:assert/strict'

const require = createRequire(import.meta.url)
const { directoryPathFromFiles } = require('./workspaceUpload.js')

test('从 webkitRelativePath 反推出文件夹绝对路径', () => {
  const files = [
    { path: 'D:\\myproj\\src\\a.js', webkitRelativePath: 'myproj/src/a.js' },
    { path: 'D:\\myproj\\README.md', webkitRelativePath: 'myproj/README.md' }
  ]
  assert.equal(directoryPathFromFiles(files), 'D:\\myproj')
})

test('正斜杠路径同样能反推', () => {
  const files = [{ path: '/home/me/proj/a.txt', webkitRelativePath: 'proj/a.txt' }]
  assert.equal(directoryPathFromFiles(files), '/home/me/proj')
})

test('浏览器不给 path 时返回 null，交由用户手动绑定', () => {
  const files = [{ name: 'a.txt', webkitRelativePath: 'proj/a.txt' }]
  assert.equal(directoryPathFromFiles(files), null)
})

test('path 与相对路径对不上时不猜', () => {
  const files = [{ path: 'D:\\elsewhere\\a.txt', webkitRelativePath: 'proj/a.txt' }]
  assert.equal(directoryPathFromFiles(files), null)
})
