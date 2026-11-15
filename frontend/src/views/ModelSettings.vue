<template>
  <div class="model-settings">
    <n-space justify="space-between" align="center" style="margin-bottom: 16px">
      <n-h2 style="margin: 0">⚙️ 模型配置</n-h2>
      <n-space>
        <n-button @click="router.push('/chat')">← 返回对话</n-button>
        <n-button type="primary" @click="openCreate">+ 新增模型</n-button>
      </n-space>
    </n-space>

    <n-card>
      <n-alert :type="statusType" :show-icon="true" style="margin-bottom: 16px">
        {{ statusText }}
      </n-alert>

      <n-data-table :columns="columns" :data="models" :row-key="row => row.id" :loading="loading" />
    </n-card>

    <ModelFormDialog v-model:show="showModelForm" :model="editingModel" @saved="load" />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, h } from 'vue'
import { useRouter } from 'vue-router'
import { listModels, getModelStatus, deleteModel, setDefaultModel } from '@/api/model'
import { normalizeModelStatus } from '@/utils/modelAvailability'
import ModelFormDialog from '@/components/ModelFormDialog.vue'
import { NButton, NTag, NPopconfirm, NSpace, NAlert, NH2, NCard, NDataTable, useMessage } from 'naive-ui'

const router = useRouter()
const message = useMessage()
const models = ref([])
const loading = ref(false)
const showModelForm = ref(false)
const editingModel = ref(null)
const modelStatus = ref(normalizeModelStatus(null))

const statusType = computed(() => modelStatus.value.modelConfigured ? 'info' : 'warning')
const statusText = computed(() => {
  if (!modelStatus.value.modelConfigured) {
    return '当前没有可用模型。请先新增一个模型并保存，然后返回对话页开始使用。'
  }
  if (modelStatus.value.systemModelAvailable) {
    return '系统模型已配置；也可以添加自己的模型。所有 API 密钥均加密存储在服务端，列表与接口不会返回明文。'
  }
  return '已保存的模型密钥均加密存储在服务端，列表与接口不会返回明文；当前使用你保存的模型。'
})

const columns = [
  { title: '厂商', key: 'provider', render: r => r.provider || '自定义' },
  { title: '接口地址', key: 'baseUrl', ellipsis: { tooltip: true } },
  { title: '模型名', key: 'modelName' },
  { title: '默认', key: 'defaultModel', render: r => r.defaultModel ? h(NTag, { type: 'success' }, { default: () => '默认' }) : '' },
  {
    title: '操作', key: 'actions',
    render: (r) => h(NSpace, {}, {
      default: () => [
        h(NButton, { size: 'small', disabled: r.defaultModel, onClick: () => doSetDefault(r) }, { default: () => '设为默认' }),
        h(NButton, { size: 'small', onClick: () => openEdit(r) }, { default: () => '编辑' }),
        h(NPopconfirm, { onPositiveClick: () => doDelete(r) }, {
          default: () => '确认删除该模型？',
          trigger: () => h(NButton, { size: 'small', type: 'error' }, { default: () => '删除' })
        })
      ]
    })
  }
]

const loadStatus = async () => {
  try { modelStatus.value = normalizeModelStatus(await getModelStatus()) }
  catch (e) { console.error('[模型] 加载模型状态失败:', e) }
}

const load = async () => {
  loading.value = true
  try {
    const result = await listModels()
    models.value = (result || []).map(normalizeModel)
    await loadStatus()
  } catch (e) { message.error('加载失败') }
  finally { loading.value = false }
}

const normalizeModel = (model) => ({
  ...model,
  baseUrl: model.baseUrl ?? model.base_url ?? '',
  modelName: model.modelName ?? model.model_name ?? '',
  defaultModel: model.defaultModel ?? model.default_model ?? false
})

const openCreate = () => { editingModel.value = null; showModelForm.value = true }

const openEdit = (r) => { editingModel.value = r; showModelForm.value = true }

const doSetDefault = async (r) => {
  try { await setDefaultModel(r.id); message.success('已设为默认'); await load() } catch (e) { message.error('操作失败') }
}

const doDelete = async (r) => {
  try { await deleteModel(r.id); message.success('已删除'); await load() } catch (e) { message.error('删除失败') }
}

onMounted(load)
</script>

<style scoped>
.model-settings { max-width: 960px; margin: 24px auto; padding: 0 16px; }
</style>
