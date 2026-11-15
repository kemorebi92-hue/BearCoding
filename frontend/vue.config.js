const { defineConfig } = require('@vue/cli-service')

// ===== SQLite-3D-B：可切换的 dev-server 代理 =====
// 目的：同一份源码可以起两个前端实例——
//   5173 → 8082（MySQL，默认，行为不变）
//   5174 → 8083（SQLite，靠下面两个环境变量切换）
//
// 不设置 VUE_APP_PROXY_TARGET 时，一切与之前完全一致：目标 8082、端口 5173、不改 Origin。
const PROXY_TARGET = process.env.VUE_APP_PROXY_TARGET || 'http://localhost:8082'
const PROXY_ORIGIN = process.env.VUE_APP_PROXY_ORIGIN || 'http://localhost:5173'
const CUSTOM_TARGET = Boolean(process.env.VUE_APP_PROXY_TARGET)

// 后端 CORS 只放行 http://localhost:5173（见 CorsConfig 与各 Controller 的 @CrossOrigin），
// 而 dev-server 代理会把浏览器的 Origin 原样转发给后端。换到 5174 后，POST 会因为
// Origin 不在白名单里被 403。这里在自定义目标时把 Origin 对齐到 PROXY_ORIGIN。
//
// 注意：这是为了满足后端的 CORS 白名单，不是身份认证机制。
// 本机身份由 app.auth.mode=local 固定为 userId=1，后端不读 Origin 判身份。
function alignOrigin(proxyReq) {
  if (CUSTOM_TARGET) proxyReq.setHeader('Origin', PROXY_ORIGIN)
}

function proxyOptions(extra) {
  return Object.assign({
    target: PROXY_TARGET,
    changeOrigin: true,
    onProxyReq: alignOrigin
  }, extra)
}

module.exports = defineConfig({
  transpileDependencies: true,
  chainWebpack: (config) => {
    config.plugin('html').tap((args) => {
      args[0].title = 'BearCoding'
      return args
    })
  },
  lintOnSave: false,
  devServer: {
    port: 5173,
    open: true,
    // ResizeObserver 的浏览器良性警告会被 dev-server 当成 runtime error 弹红色遮罩，
    // 挡住页面且与业务无关；只关运行时遮罩，编译错误仍在终端和页面上显示。
    client: {
      overlay: {
        runtimeErrors: false
      }
    },
    // ⚠️ 必须显式关掉压缩：
    // dev-server 的压缩中间件会给 /ai/stream 的响应加上 Content-Encoding: br，
    // 而压缩算法要攒够缓冲才输出 —— 结果就是"逐字流"在浏览器侧变成"一大坨一起出来"。
    // 直连后端（curl 不带 Accept-Encoding）看不出问题，浏览器一定带，所以只在页面上暴露。
    compress: false,
    proxy: {
      '/ai': proxyOptions({
        ws: true,
        // 双保险：明确要求上游不压缩，并抹掉任何可能残留的 content-encoding
        headers: { 'Accept-Encoding': 'identity' },
        onProxyRes(proxyRes) {
          delete proxyRes.headers['content-encoding']
        }
      }),
      '/api': proxyOptions(),
      '/user': proxyOptions()
    }
  }
})
