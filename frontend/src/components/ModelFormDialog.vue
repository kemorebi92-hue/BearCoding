<template>
  <n-modal
    :show="show"
    :title="isEdit ? '编辑模型' : '新增模型'"
    preset="card"
    style="width: 560px"
    @update:show="val => emit('update:show', val)"
  >
    <n-form :model="form" label-placement="top">
      <n-form-item label="厂商">
        <n-select
          v-model:value="form.provider"
          :options="providerOptions"
          placeholder="选择厂商"
          filterable
          @update:value="onProviderChange"
        />
      </n-form-item>
      <n-form-item v-if="form.provider === CUSTOM_PROVIDER" label="自定义厂商名称">
        <n-input v-model:value="form.customProvider" placeholder="例如：小米、OpenRouter、本地 Ollama" />
      </n-form-item>
      <n-form-item label="接口地址 (base_url)">
        <n-input v-model:value="form.baseUrl" placeholder="https://api.deepseek.com" />
      </n-form-item>
      <n-form-item label="模型名">
        <n-input v-model:value="form.modelName" placeholder="deepseek-chat" />
      </n-form-item>
      <n-form-item label="API 密钥">
        <n-input
          v-model:value="form.apiKey"
          type="password"
          show-password-on="click"
          :placeholder="isEdit ? '留空则不修改' : 'sk-...'"
        />
      </n-form-item>
      <n-form-item label="设为默认">
        <n-switch v-model:value="form.defaultModel" />
      </n-form-item>
    </n-form>
    <template #footer>
      <n-space justify="end">
        <n-button @click="close">取消</n-button>
        <n-button type="primary" :loading="saving" @click="save">保存</n-button>
      </n-space>
    </template>
  </n-modal>
</template>

<script setup>
import { ref, reactive, computed, watch } from 'vue'
import { addModel, updateModel, PROVIDER_PRESETS } from '@/api/model'
import { NButton, NSpace, NForm, NFormItem, NInput, NSelect, NSwitch, NModal, useMessage } from 'naive-ui'

const props = defineProps({
  show: { type: Boolean, default: false },
  model: { type: Object, default: null }
})

const emit = defineEmits(['update:show', 'saved'])

const message = useMessage()
const saving = ref(false)

const CUSTOM_PROVIDER = 'custom'
const form = reactive({
  provider: null,
  customProvider: '',
  baseUrl: '',
  apiKey: '',
  modelName: '',
  defaultModel: false
})

const isEdit = computed(() => Boolean(props.model))
const editingId = computed(() => props.model?.id ?? null)

const providerOptions = [
  ...Object.entries(PROVIDER_PRESETS).map(([key, value]) => ({ label: value.label, value: key })),
  { label: '自定义厂商', value: CUSTOM_PROVIDER }
]

const onProviderChange = (val) => {
  const p = PROVIDER_PRESETS[val]
  if (p) {
    form.baseUrl = p.baseUrl
    form.modelName = p.modelName
    form.customProvider = ''
  }
}

const resetForm = () => {
  form.provider = null
  form.customProvider = ''
  form.baseUrl = ''
  form.apiKey = ''
  form.modelName = ''
  form.defaultModel = false
}

const fillForm = (model) => {
  const isPreset = Boolean(PROVIDER_PRESETS[model.provider])
  form.provider = isPreset ? model.provider : CUSTOM_PROVIDER
  form.customProvider = isPreset ? '' : (model.provider || '')
  form.baseUrl = model.baseUrl || ''
  form.modelName = model.modelName || ''
  form.apiKey = ''
  form.defaultModel = Boolean(model.defaultModel)
}

watch(() => props.show, (visible) => {
  if (!visible) return
  if (props.model) fillForm(props.model)
  else resetForm()
})

const close = () => emit('update:show', false)

const save = async () => {
  if (saving.value) return
  const provider = form.provider === CUSTOM_PROVIDER ? form.customProvider.trim() : form.provider
  if (!provider) { message.warning('请选择厂商或填写自定义厂商名称'); return }
  if (!form.baseUrl.trim() || !form.modelName.trim()) { message.warning('接口地址和模型名必填'); return }
  if (!editingId.value && !form.apiKey.trim()) { message.warning('新增模型时 API 密钥必填'); return }
  saving.value = true
  try {
    const payload = {
      provider,
      baseUrl: form.baseUrl.trim(),
      apiKey: form.apiKey,
      modelName: form.modelName.trim(),
      defaultModel: form.defaultModel
    }
    if (editingId.value) await updateModel(editingId.value, payload)
    else await addModel(payload)
    message.success('已保存')
    close()
    emit('saved')
  } catch (e) { message.error(e.response?.data?.message || '保存失败') }
  finally { saving.value = false }
}
</script>
