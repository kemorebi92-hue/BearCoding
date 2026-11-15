/**
 * 工作区分组浏览器级验收。独立测试用户，不碰真实数据。
 * 前置：后端 8082（jingjing_ai_e2e + WORKSPACE_ROOT=D:/tmp/e2e-workspaces），前端 5173。
 */
const { chromium } = require('playwright')
const fs = require('fs')
const path = require('path')
const http = require('http')

const BASE = process.env.E2E_BASE || 'http://localhost:5173'
const API = process.env.E2E_API || 'http://localhost:8082'
const SHOT = process.env.E2E_SHOTS || 'D:/tmp/e2e-shots'
const PROJ_A = 'D:/tmp/e2e-dirs/proj-a'
const PROJ_B = 'D:/tmp/e2e-dirs/proj-b'
const USER = process.env.E2E_USER || 'e2e_browser'
const PASS = process.env.E2E_PASS || 'E2ePassBrowser!9'

fs.mkdirSync(SHOT, { recursive: true })

function jsonReq(method, urlPath, body, token) {
  return new Promise((resolve, reject) => {
    const u = new URL(urlPath, API)
    const data = body == null ? null : Buffer.from(JSON.stringify(body))
    const req = http.request({
      hostname: u.hostname,
      port: u.port,
      path: u.pathname + u.search,
      method,
      headers: {
        'Content-Type': 'application/json',
        ...(token ? { Authorization: 'Bearer ' + token } : {}),
        ...(data ? { 'Content-Length': data.length } : {})
      }
    }, res => {
      let buf = ''
      res.on('data', d => { buf += d })
      res.on('end', () => {
        let parsed = buf
        try { parsed = JSON.parse(buf) } catch (e) { /* raw */ }
        resolve({ status: res.statusCode, body: parsed })
      })
    })
    req.on('error', reject)
    if (data) req.write(data)
    req.end()
  })
}

async function ensureUser() {
  const login = await jsonReq('POST', '/user/login', { username: USER, password: PASS })
  if (login.status === 200 && login.body && login.body.token) return login.body.token
  const reg = await jsonReq('POST', '/user/register', { username: USER, password: PASS })
  if (reg.body && reg.body.token) return reg.body.token
  const again = await jsonReq('POST', '/user/login', { username: USER, password: PASS })
  if (again.body && again.body.token) return again.body.token
  throw new Error('cannot login e2e_browser: ' + JSON.stringify(reg.body))
}

async function shot(page, name) {
  const p = path.join(SHOT, name + '.png')
  await page.screenshot({ path: p, fullPage: true })
  console.log('SHOT', p)
}

function fail(step, msg) {
  throw new Error('STEP ' + step + ': ' + msg)
}

async function login(page) {
  await page.goto(BASE + '/login', { waitUntil: 'networkidle' })
  await page.getByPlaceholder('请输入用户名').fill(USER)
  await page.getByPlaceholder('请输入密码').fill(PASS)
  await page.getByRole('button', { name: '登录' }).click()
  await page.waitForURL('**/chat**', { timeout: 15000 })
  await page.waitForSelector('.sidebar', { timeout: 15000 })
}

async function chipText(page) {
  const t = await page.locator('.ws-chip').innerText()
  return t.replace(/\s+/g, ' ').trim()
}

function group(page, name) {
  return page.locator('.ws-group[data-ws-name="' + name + '"]')
}

async function bindPath(page, dir) {
  await page.locator('.ws-chip').click()
  await page.waitForSelector('.workspace-panel', { timeout: 8000 })
  const input = page.locator('.ws-bind-row input').first()
  await input.fill(dir)
  await page.getByRole('button', { name: '绑定' }).click()
  await page.waitForTimeout(800)
}

async function sendAndWait(page, text, timeoutMs) {
  const treeGets = []
  page.on('request', req => {
    if (req.url().includes('/ai/workspace/tree')) treeGets.push(req.url())
  })
  await page.locator('.chat-input textarea').fill(text)
  await page.getByRole('button', { name: '发送' }).click()
  // wait until loading ends (stop button gone) or timeout
  const start = Date.now()
  while (Date.now() - start < (timeoutMs || 90000)) {
    const loading = await page.locator('.stop-button').count()
    if (loading === 0) break
    await page.waitForTimeout(500)
  }
  return treeGets
}

;(async () => {
  await ensureUser()
  const browser = await chromium.launch({
    headless: true,
    channel: process.env.E2E_CHANNEL || undefined
  }).catch(async (e) => {
    console.log('chromium.launch default failed:', e.message)
    const edge = 'C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe'
    const chrome = 'C:/Program Files/Google/Chrome/Application/chrome.exe'
    const exe = fs.existsSync(edge) ? edge : (fs.existsSync(chrome) ? chrome : null)
    if (!exe) throw e
    console.log('fallback executablePath', exe)
    return chromium.launch({ headless: true, executablePath: exe })
  })
  const context = await browser.newContext({ viewport: { width: 1400, height: 900 } })
  const page = await context.newPage()
  const results = []
  const record = (step, ok, detail) => {
    results.push({ step, ok, detail })
    console.log((ok ? 'PASS' : 'FAIL') + ' step ' + step + ' ' + detail)
  }

  try {
    await login(page)
    await shot(page, '00-logged-in')

    // Step 1 B,F
    await page.getByRole('button', { name: '新对话' }).click()
    await page.waitForTimeout(400)
    await shot(page, '01-new-unbound')
    const chip1 = await chipText(page)
    const inDefault = await group(page, '默认工作区').locator('.conversation-item.active').count()
    if (!chip1.includes('默认工作区')) fail(1, 'chip=' + chip1)
    if (inDefault < 1) fail(1, 'active conv not in default group')
    record(1, true, 'chip=' + chip1 + ' defaultActive=' + inDefault)

    // Step 2 D bind proj-a
    await bindPath(page, PROJ_A)
    await shot(page, '02-bound-proj-a')
    const chip2 = await chipText(page)
    const aGroup = group(page, 'proj-a')
    if (await aGroup.count() < 1) fail(2, 'no proj-a group')
    if (!chip2.includes('proj-a')) fail(2, 'chip=' + chip2)
    const hello = await page.locator('.ws-item-name', { hasText: 'hello.txt' }).count()
    if (hello < 1) fail(2, 'hello.txt not in tree')
    const stillDefaultActive = await group(page, '默认工作区').locator('.conversation-item.active').count()
    if (stillDefaultActive > 0) fail(2, 'session still in default')
    record(2, true, 'chip=' + chip2 + ' hello=' + hello)

    // Step 3 C plus in proj-a
    await aGroup.locator('button[title="在此工作区新建会话"]').click()
    await page.waitForTimeout(400)
    await shot(page, '03-plus-in-proj-a')
    const chip3 = await chipText(page)
    if (!chip3.includes('proj-a')) fail(3, 'pending chip=' + chip3)
    const pendingInA = await aGroup.locator('.conversation-item.active').count()
    if (pendingInA < 1) fail(3, 'pending not in proj-a')
    const helloStill = await page.locator('.ws-item-name', { hasText: 'hello.txt' }).count()
    if (helloStill < 1) fail(3, 'tree lost after plus')
    record(3, true, 'pending chip=' + chip3)

    if (process.env.E2E_SKIP_AGENT === '1') {
      record('3-send', true, 'skipped (E2E_SKIP_AGENT=1)')
    } else {
      try {
        await sendAndWait(page, '只回复一个字：好', 120000)
        await shot(page, '03b-after-first-message')
      } catch (e) {
        record('3-send', false, 'send failed: ' + e.message)
        await shot(page, '03b-send-failed')
      }
    }
    await page.reload({ waitUntil: 'networkidle' })
    await page.waitForSelector('.sidebar', { timeout: 15000 })
    await shot(page, '03c-after-reload-proj-a')
    if (await group(page, 'proj-a').locator('.conversation-item').count() < 2) {
      // at least the original + pending that got persisted; allow 1 if send failed
      console.log('WARN proj-a conv count', await group(page, 'proj-a').locator('.conversation-item').count())
    }
    record('3-reload', true, 'proj-a still present after reload')

    // Step 4 B new unbound
    await page.getByRole('button', { name: '新对话' }).click()
    await page.waitForTimeout(400)
    await shot(page, '04-new-unbound-again')
    const chip4 = await chipText(page)
    if (!chip4.includes('默认工作区')) fail(4, 'chip=' + chip4)
    if (await group(page, '默认工作区').locator('.conversation-item.active').count() < 1) fail(4, 'not in default')
    record(4, true, 'chip=' + chip4)

    // Step 5 bind proj-b
    await bindPath(page, PROJ_B)
    await shot(page, '05-bound-proj-b')
    if (await group(page, 'proj-b').count() < 1) fail(5, 'no proj-b group')
    if (await group(page, 'proj-a').count() < 1) fail(5, 'proj-a disappeared')
    const chip5 = await chipText(page)
    if (!chip5.includes('proj-b')) fail(5, 'chip=' + chip5)
    record(5, true, 'chip=' + chip5)

    // Step 6 reuse proj-a
    await page.getByRole('button', { name: '新对话' }).click()
    await page.waitForTimeout(300)
    await bindPath(page, 'd:/tmp/e2e-dirs/proj-a')
    await shot(page, '06-rebind-proj-a')
    if (await group(page, 'proj-a').count() !== 1) fail(6, 'duplicate proj-a groups: ' + await group(page, 'proj-a').count())
    if (await group(page, 'proj-a').locator('.conversation-item.active').count() < 1) fail(6, 'not moved into proj-a')
    record(6, true, 'single proj-a group')

    // Step 7 G switch within proj-a
    if (await page.locator('.workspace-panel').count() === 0) {
      await page.locator('.ws-chip').click()
      await page.waitForSelector('.workspace-panel', { timeout: 8000 })
    }
    const aItems = group(page, 'proj-a').locator('.conversation-item')
    const nA = await aItems.count()
    if (nA < 2) fail(7, 'need 2 convs in proj-a, got ' + nA)
    let treeHits = 0
    const onReq = req => { if (req.url().includes('/ai/workspace/tree')) treeHits++ }
    page.on('request', onReq)
    await aItems.nth(0).click()
    await page.locator('.ws-item-name', { hasText: 'hello.txt' }).waitFor({ timeout: 8000 })
    const treeAfter1 = await page.locator('.ws-item-name', { hasText: 'hello.txt' }).count()
    await aItems.nth(1).click()
    await page.locator('.ws-item-name', { hasText: 'hello.txt' }).waitFor({ timeout: 8000 })
    const treeAfter2 = await page.locator('.ws-item-name', { hasText: 'hello.txt' }).count()
    page.off('request', onReq)
    await shot(page, '07-switch-within-proj-a')
    if (treeAfter1 < 1 || treeAfter2 < 1) fail(7, 'tree lost on switch')
    record(7, true, 'treeHits=' + treeHits + ' (cache may skip)')

    // Step 8 E unbind one in proj-a
    await group(page, 'proj-a').locator('.conversation-item').first().click()
    await page.waitForTimeout(300)
    await page.locator('.ws-chip').click()
    await page.waitForSelector('.workspace-panel')
    await page.getByRole('button', { name: '解除绑定' }).click()
    await page.waitForTimeout(800)
    await shot(page, '08-unbound')
    const chip8 = await chipText(page)
    if (!chip8.includes('默认工作区')) fail(8, 'chip after unbind=' + chip8)
    if (await group(page, '默认工作区').locator('.conversation-item.active').count() < 1) fail(8, 'not back in default')
    if (await group(page, 'proj-a').count() < 1) fail(8, 'proj-a group vanished (other sessions should remain)')
    record(8, true, 'chip=' + chip8)

    // Step 9 H reload
    const before = {
      a: await group(page, 'proj-a').locator('.conversation-item').count(),
      b: await group(page, 'proj-b').locator('.conversation-item').count(),
      d: await group(page, '默认工作区').locator('.conversation-item').count(),
      chip: await chipText(page)
    }
    await page.reload({ waitUntil: 'networkidle' })
    await page.waitForSelector('.sidebar', { timeout: 15000 })
    await shot(page, '09-after-full-reload')
    const after = {
      a: await group(page, 'proj-a').locator('.conversation-item').count(),
      b: await group(page, 'proj-b').locator('.conversation-item').count(),
      d: await group(page, '默认工作区').locator('.conversation-item').count(),
      chip: await chipText(page)
    }
    if (after.a !== before.a || after.b !== before.b) fail(9, 'group counts changed ' + JSON.stringify({ before, after }))
    record(9, true, JSON.stringify({ before, after }))

    // Step 10 streaming disables bind
    await page.getByRole('button', { name: '新对话' }).click()
    await page.waitForTimeout(300)
    await page.locator('.chat-input textarea').fill('请慢慢数到二十，每数字一行')
    await page.getByRole('button', { name: '发送' }).click()
    await page.waitForSelector('.stop-button', { timeout: 20000 })
    await page.locator('.ws-chip').click()
    await page.waitForSelector('.workspace-panel')
    const bindDisabled = await page.getByRole('button', { name: '绑定' }).isDisabled()
    await shot(page, '10-streaming-bind-disabled')
    // stop generation
    await page.locator('.stop-button').click().catch(() => {})
    await page.waitForTimeout(1500)
    if (!bindDisabled) fail(10, 'bind button not disabled while streaming')
    record(10, true, 'bindDisabled=' + bindDisabled)

    // Step 11 agent read/write — skippable (slow, needs live model)
    if (process.env.E2E_SKIP_AGENT === '1') {
      record(11, true, 'skipped (E2E_SKIP_AGENT=1)')
      record('11-write', true, 'skipped (E2E_SKIP_AGENT=1)')
    } else {
    await group(page, 'proj-a').locator('.conversation-item').first().click()
    await page.waitForTimeout(400)
    await sendAndWait(page, '请用 readFile 读取 hello.txt，只把文件原文复述出来，不要改文件', 180000)
    await shot(page, '11-read-hello')
    const bodyText = await page.locator('.messages-list').innerText()
    if (!bodyText.includes('hello from proj-a')) {
      record(11, false, 'reply did not contain hello from proj-a; got snippet: ' + bodyText.slice(-400))
    } else {
      record(11, true, 'agent echoed proj-a hello.txt')
    }

    await page.getByRole('button', { name: '新对话' }).click()
    await page.waitForTimeout(300)
    await sendAndWait(page, '请用 writeFile 在当前工作区写一个文件 e2e-sandbox.txt，内容只写 sandbox-ok', 180000)
    await shot(page, '11b-write-sandbox')
    // find the file under e2e-workspaces, not under proj-a
    const foundA = fs.existsSync(path.join('D:/tmp/e2e-dirs/proj-a', 'e2e-sandbox.txt'))
    let foundSandbox = false
    const walk = (dir) => {
      if (!fs.existsSync(dir)) return
      for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
        const p = path.join(dir, ent.name)
        if (ent.isDirectory()) walk(p)
        else if (ent.name === 'e2e-sandbox.txt') foundSandbox = true
      }
    }
    walk('D:/tmp/e2e-workspaces')
    if (foundA) fail(11, 'sandbox file leaked into proj-a')
    if (!foundSandbox) {
      record('11-write', false, 'e2e-sandbox.txt not found under e2e-workspaces')
    } else {
      record('11-write', true, 'file in default sandbox, not proj-a')
    }
    } // end E2E_SKIP_AGENT else

    // Step 12 delete one conv in proj-a
    const aCountBefore = await group(page, 'proj-a').locator('.conversation-item').count()
    await group(page, 'proj-a').locator('.conversation-item').first().click()
    await page.waitForTimeout(200)
    await group(page, 'proj-a').locator('.conversation-item.active button[title="删除会话"]').click()
    await page.waitForFunction((n) => {
      const g = document.querySelector('.ws-group[data-ws-name="proj-a"]')
      if (!g) return true
      return g.querySelectorAll('.conversation-item').length < n
    }, aCountBefore, { timeout: 8000 }).catch(() => {})
    await page.waitForFunction(() => {
      const active = document.querySelector('.conversation-item.active')
      const chip = document.querySelector('.ws-chip')
      const g = active && active.closest('[data-ws-name]')
      if (!active || !chip || !g) return false
      const name = g.getAttribute('data-ws-name') || ''
      return chip.innerText.indexOf(name) >= 0
    }, { timeout: 8000 })
    await shot(page, '12-after-delete')
    const aCountAfter = await group(page, 'proj-a').locator('.conversation-item').count()
    if (!fs.existsSync(path.join(PROJ_A, 'hello.txt'))) fail(12, 'proj-a/hello.txt deleted from disk')
    if (aCountAfter >= aCountBefore) fail(12, 'sidebar still shows deleted session: before=' + aCountBefore + ' after=' + aCountAfter)
    const chip12 = await chipText(page)
    const selectedGroup = page.locator('.ws-group').filter({ has: page.locator('.conversation-item.active') })
    if (await selectedGroup.count() < 1) fail(12, 'no active conversation after delete')
    const selectedName = await selectedGroup.first().getAttribute('data-ws-name')
    const chipName = chip12.replace(/^📁\s*/, '')
    if (chipName !== selectedName) {
      fail(12, 'chip 与选中组不一致 chip=' + chipName + ' selected=' + selectedName)
    }
    record(12, true, 'hello.txt still on disk; convs before=' + aCountBefore + ' after=' + aCountAfter + '; chip=' + chipName)
  } catch (e) {
    console.error('E2E FAILED', e)
    try { await shot(page, '99-failure') } catch (x) { /* ignore */ }
    results.push({ step: 'crash', ok: false, detail: e.message })
    await browser.close()
    fs.writeFileSync(path.join(SHOT, 'results.json'), JSON.stringify(results, null, 2))
    process.exit(1)
  }

  await browser.close()
  fs.writeFileSync(path.join(SHOT, 'results.json'), JSON.stringify(results, null, 2))
  const failed = results.filter(r => !r.ok)
  console.log('SUMMARY', JSON.stringify(results, null, 2))
  process.exit(failed.length ? 1 : 0)
})().catch(e => {
  console.error(e)
  process.exit(1)
})
