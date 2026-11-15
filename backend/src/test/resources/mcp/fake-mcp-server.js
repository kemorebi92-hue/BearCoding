#!/usr/bin/env node
// 测试用的最小 stdio MCP（协议 2024-11-05，一行一条 JSON-RPC）。
// SERVER_ROLE=other 时只提供 peek，给跨服务污染用。
// TOOL_DESC 改 read_item 的说明，用来测定义哈希。
const readline = require('readline')

const role = process.env.SERVER_ROLE || 'main'
const readDesc = process.env.TOOL_DESC || 'read an item'

const tools = role === 'other'
  ? [{
      name: 'peek',
      description: 'peek from the other service',
      inputSchema: { type: 'object', properties: {} }
    }]
  : [
      {
        name: 'read_item',
        description: readDesc,
        inputSchema: { type: 'object', properties: { id: { type: 'string' } } },
        annotations: { readOnlyHint: true }
      },
      {
        name: 'write_note',
        description: 'write a note ' + readDesc,
        inputSchema: { type: 'object', properties: { text: { type: 'string' } } }
      },
      {
        name: 'delete_item',
        description: 'delete one item',
        inputSchema: { type: 'object', properties: { id: { type: 'string' } } },
        annotations: { readOnlyHint: true }
      },
      {
        name: 'wipe_all',
        description: 'wipe everything',
        inputSchema: { type: 'object', properties: {} },
        annotations: { destructiveHint: true }
      },
      {
        name: 'archive_box',
        description: 'archive a box',
        inputSchema: { type: 'object', properties: {} },
        annotations: { destructiveHint: true }
      },
      {
        name: 'evaluate_note',
        description: 'score a note',
        inputSchema: { type: 'object', properties: {} }
      },
      {
        name: 'unsafe_ping',
        description: 'ping unsafely',
        inputSchema: { type: 'object', properties: {} }
      },
      {
        name: 'secret_tool',
        description: 'not on the deploy whitelist',
        inputSchema: { type: 'object', properties: {} }
      },
      {
        name: 'big_text',
        description: 'return a lot of text',
        inputSchema: { type: 'object', properties: { chars: { type: 'number' } } }
      },
      {
        name: 'big_secret',
        description: '返回一份很长的内部记录',
        inputSchema: { type: 'object', properties: {} }
      }
    ]

function send(obj) {
  process.stdout.write(JSON.stringify(obj) + '\n')
}

const rl = readline.createInterface({ input: process.stdin })
rl.on('line', (line) => {
  let msg
  try { msg = JSON.parse(line) } catch (e) { return }
  if (!msg || msg.id == null) return
  if (msg.method === 'initialize') {
    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: {
        protocolVersion: '2024-11-05',
        capabilities: { tools: {} },
        serverInfo: { name: 'fake-' + role, version: '0' }
      }
    })
    return
  }
  if (msg.method === 'tools/list') {
    send({ jsonrpc: '2.0', id: msg.id, result: { tools } })
    return
  }
  if (msg.method === 'tools/call') {
    const name = msg.params && msg.params.name
    const args = msg.params && msg.params.arguments
    let text = 'ok:' + name + ':' + JSON.stringify(args || {})
    if (name === 'big_text') {
      const n = Math.max(1, Number((args && args.chars) || 20000))
      // 前 40 个字符醒目，方便确认预览到底覆盖到哪里
      text = 'BEGIN-' + 'y'.repeat(Math.max(0, n - 40)) + '-END'
    }
    if (name === 'big_secret') {
      // 约 300000 字符的编号记录。编码由 SECRET_CODE 决定，嵌在约 40% 处，
      // 既不在旧截断保留的头部，也不在尾部。
      const code = process.env.SECRET_CODE || 'K-0000-0000'
      const total = 3000
      const at = Math.floor(total * 0.4)
      const lines = []
      for (let i = 1; i <= total; i++) {
        const body = i === at
          ? 'SECRET CODE: ' + code
          : '记录项 ' + String(i).padStart(4, '0') + ' ' + 'x'.repeat(80)
        lines.push(String(i).padStart(4, '0') + ' | ' + body)
      }
      text = lines.join('\n')
    }
    send({
      jsonrpc: '2.0',
      id: msg.id,
      result: { content: [{ type: 'text', text }], isError: false }
    })
    return
  }
  send({ jsonrpc: '2.0', id: msg.id, error: { code: -32601, message: 'unknown method' } })
})
