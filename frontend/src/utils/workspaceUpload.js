/**
 * 工作区上传：文件夹上传要绑定真实目录，而不是把副本拷进默认工作区。
 * 浏览器的 webkitdirectory 选不到绝对路径，这里只从 File 里尽力取（Electron / 扩展环境才有 path）。
 */

/** @param {File[]} files @returns {string|null} 选中文件夹的本机绝对路径，拿不到返回 null */
export function directoryPathFromFiles(files) {
  const list = Array.from(files || [])
  const withPath = list.find(f => typeof f.path === 'string' && f.path.trim())
  if (!withPath) return null
  const abs = withPath.path.trim()
  const rel = (withPath.webkitRelativePath || '').replace(/\\/g, '/')
  if (!rel) return null
  const sep = abs.includes('\\') ? '\\' : '/'
  const relNative = rel.split('/').join(sep)
  if (!abs.endsWith(relNative)) return null
  // webkitRelativePath 的第一段就是被选中的文件夹名：abs 去掉它之后要再接回这一段，
  // 否则 D:\myproj\src\a.js 会被算成 D:（把 myproj 也一起切掉了）。
  const folder = rel.split('/')[0]
  const prefix = abs.slice(0, abs.length - relNative.length)
  const root = (prefix + folder).replace(/[\\/]+$/, '')
  return root || null
}
