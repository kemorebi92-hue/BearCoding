import { createRequire } from 'module'
import test from 'node:test'
import assert from 'node:assert/strict'

const require = createRequire(import.meta.url)
const { formatPickDirectoryError } = require('./pickDirectoryError.js')

test('404/501/405 提示手动填写路径', () => {
  assert.match(formatPickDirectoryError({ response: { status: 404 } }), /请手动填写路径/)
  assert.match(formatPickDirectoryError({ response: { status: 501 } }), /请手动填写路径/)
  assert.match(formatPickDirectoryError({ response: { status: 405 } }), /请手动填写路径/)
})

test('后端返回 error 字段时优先展示', () => {
  assert.equal(
    formatPickDirectoryError({ response: { status: 500, data: { error: '磁盘忙' } } }),
    '磁盘忙'
  )
})

test('超时与其它错误', () => {
  assert.match(formatPickDirectoryError({ code: 'ECONNABORTED' }), /超时/)
  assert.equal(formatPickDirectoryError({ message: 'Network Error' }), 'Network Error')
  assert.equal(formatPickDirectoryError({}), '后端没开或请求失败')
})
