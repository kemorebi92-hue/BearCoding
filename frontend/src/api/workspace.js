import request from '@/api/request'

// 上传文件到当前会话的工作区（files 与 paths 一一对应，paths 为相对路径，支持保留文件夹层级）
export const uploadWorkspace = (conversationId, files, paths, workspaceId) => {
  const fd = new FormData()
  files.forEach(f => fd.append('files', f))
  paths.forEach(p => fd.append('paths', p))
  fd.append('conversationId', conversationId)
  if (workspaceId != null && workspaceId !== '') fd.append('workspaceId', workspaceId)
  return request.post('/ai/workspace/upload', fd, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 180000
  })
}

// 工作区某一层的直接子项（dir 为空时为根目录）
export const getWorkspaceTree = (conversationId, workspaceId, dir) =>
  request.get('/ai/workspace/tree', { params: { conversationId, workspaceId, dir: dir || undefined } })

// 预览单个文件（返回文本内容）
export const getWorkspaceFile = (conversationId, path, workspaceId) =>
  request.get('/ai/workspace/file', { params: { conversationId, path, workspaceId } })

// 下载单个文件（返回 Blob）
export const downloadWorkspaceFile = (conversationId, path, workspaceId) =>
  request.get('/ai/workspace/file', {
    params: { conversationId, path, download: true, workspaceId },
    responseType: 'blob'
  })

// 清空工作区
export const clearWorkspace = (conversationId) =>
  request.delete('/ai/workspace', { params: { conversationId } })

// 当前工作区信息（mode: bound/default，root: 绝对路径）
export const getWorkspaceInfo = (conversationId, workspaceId) =>
  request.get('/ai/workspace/info', { params: { conversationId, workspaceId } })

// 绑定一个真实绝对路径作为本会话工作区
export const bindWorkspace = (conversationId, path) =>
  request.post('/ai/workspace/bind', { conversationId, path })

// 在跑后端的这台电脑上弹出系统文件夹选择框（浏览器给不了绝对路径）
export const pickWorkspaceDirectory = () =>
  request.post('/ai/workspace/pick-directory', {}, { timeout: 180000 })

// 解除绑定，回到默认工作区
export const unbindWorkspace = (conversationId) =>
  request.post('/ai/workspace/unbind', { conversationId })

/** 侧边栏一次拿齐：已绑定组 + 默认工作区会话 */
export const listWorkspaces = () => request.get('/ai/workspaces')

export const renameWorkspace = (id, name) =>
  request.patch(`/ai/workspaces/${id}`, { name })

export const deleteWorkspace = (id) =>
  request.delete(`/ai/workspaces/${id}`)
