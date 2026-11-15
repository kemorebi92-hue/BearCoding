import request from '@/api/request'

// 用户模型配置相关接口（后端 /ai/models，受登录保护）
export const listModels = () => request.get('/ai/models')

// 首屏引导状态：系统模型是否真的可用、当前用户有没有模型（不返回密钥）
export const getModelStatus = () => request.get('/ai/models/status')

export const addModel = (data) => request.post('/ai/models', data)

export const updateModel = (id, data) => request.put(`/ai/models/${id}`, data)

export const deleteModel = (id) => request.delete(`/ai/models/${id}`)

export const setDefaultModel = (id) => request.post(`/ai/models/${id}/default`)

// 常用国产模型厂商预设（选了厂商自动带出接口地址与推荐模型名）
export const PROVIDER_PRESETS = {
  deepseek: {
    label: 'DeepSeek',
    baseUrl: 'https://api.deepseek.com',
    modelName: 'deepseek-chat'
  },
  qwen: {
    label: '通义千问 (阿里)',
    baseUrl: 'https://dashscope.aliyuncs.com/compatible-mode/v1',
    modelName: 'qwen-plus'
  },
  glm: {
    label: '智谱 GLM',
    baseUrl: 'https://open.bigmodel.cn/api/paas/v4',
    modelName: 'glm-4-flash'
  },
  kimi: {
    label: 'Kimi (月之暗面)',
    baseUrl: 'https://api.moonshot.cn/v1',
    modelName: 'moonshot-v1-8k'
  }
}
