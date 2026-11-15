import request from './request'

/** 当前用户的 MCP 工具和生效策略。生效决定用接口返回的 effect，前端不算。 */
export const getMcpTools = (conversationId) =>
  request.get('/ai/mcp/tools', { params: conversationId ? { conversationId } : {} })

export const putMcpPolicy = (body) => request.put('/ai/mcp/tools', body)

/** 本会话各服务的落盘统计（要求登录且会话属于当前用户） */
export const getMcpSpillStats = (conversationId) =>
  request.get('/ai/mcp/output/stats', { params: { conversationId } })

/** 服务列表与连接状态。返回 { servers: [...] }，状态以服务端字段为准。 */
export const getMcpServers = () => request.get('/ai/mcp/servers')

/** 只校验配置，不保存、不连接。 */
export const validateMcpServer = (body) => request.post('/ai/mcp/servers/validate', body)

export const addMcpServer = (body) => request.post('/ai/mcp/servers', body)

export const updateMcpServer = (name, body) => request.put(`/ai/mcp/servers/${encodeURIComponent(name)}`, body)

export const setMcpServerEnabled = (name, enabled) =>
  request.post(`/ai/mcp/servers/${encodeURIComponent(name)}/enabled`, { enabled })

const serverAction = (name, action) =>
  request.post(`/ai/mcp/servers/${encodeURIComponent(name)}/${action}`)

export const connectMcpServer = (name) => serverAction(name, 'connect')
export const disconnectMcpServer = (name) => serverAction(name, 'disconnect')
export const reconnectMcpServer = (name) => serverAction(name, 'reconnect')
export const refreshMcpServer = (name) => serverAction(name, 'refresh')

export const deleteMcpServer = (name) => request.delete(`/ai/mcp/servers/${encodeURIComponent(name)}`)

export const getMcpCredentialStatus = (name) =>
  request.get(`/ai/mcp/servers/${encodeURIComponent(name)}/credential`)

export const saveMcpCredential = (name, token) =>
  request.post(`/ai/mcp/servers/${encodeURIComponent(name)}/credential`, { token })
