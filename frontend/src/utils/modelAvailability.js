/**
 * “有没有可用模型”的前端纯函数。
 *
 * 后端 /ai/models/status 返回：
 *   { systemModelAvailable, hasUserModels, modelConfigured }
 *
 * 只有 systemModelAvailable=true 才允许出现“系统模型”选项；否则 null 这个值
 * 会被后端解析成“系统兜底模型”，正好指向未配置的占位地址（127.0.0.1）。
 * 空库首次启动时 modelConfigured=false，界面必须显示配置引导并禁止发送。
 */

export function normalizeModelStatus(raw) {
  const source = raw || {}
  const system = source.systemModelAvailable ?? source.system_model_available ?? false
  const user = source.hasUserModels ?? source.has_user_models ?? false
  const configured = source.modelConfigured ?? source.model_configured ?? (system || user)
  return {
    systemModelAvailable: Boolean(system),
    hasUserModels: Boolean(user),
    modelConfigured: Boolean(configured)
  }
}

export function isModelReady(status) {
  return Boolean(status && status.modelConfigured)
}

/** 模型下拉选项：系统模型只在真的可用时出现。 */
export function buildModelOptions(models, status) {
  const options = []
  if (status && status.systemModelAvailable) {
    options.push({ label: '系统模型', value: null })
  }
  (models || []).forEach(model => {
    const tag = model && model.defaultModel ? ' ⭐默认' : ''
    options.push({
      label: `${(model && model.provider) || '自定义'} / ${(model && model.modelName) || ''}${tag}`,
      value: model && model.id
    })
  })
  return options
}

/** 首次启动（或模型被全部删除）时的明确引导；有模型时返回 null。 */
export function firstRunGuidance(status) {
  if (isModelReady(status)) return null
  return {
    title: '请先配置模型',
    detail: '当前数据库里还没有模型，也没有可用的系统模型。请先到「模型配置」页添加并保存一个模型，再回来聊天。',
    action: '去配置模型'
  }
}

/** 保存模型后是否应该自动回到聊天页（之前一个模型都没有时）。 */
export function shouldReturnToChatAfterSave(status) {
  return !isModelReady(status)
}
