<template>
  <aside class="sidebar" :class="{ open: sidebarOpen }">
    <div class="sidebar-header">
      <n-button type="primary" block @click="$emit('new-unbound')">
        <template #icon><n-icon><AddOutline /></n-icon></template>
        新对话
      </n-button>
      <n-button block quaternary class="mcp-entry" :class="{ active: isMcpRoute }" @click="goMcp">外部工具</n-button>
    </div>
    <div class="conversation-list">
      <div v-for="group in groups" :key="'ws-' + group.id" class="ws-group" :data-ws-name="group.name">
        <div class="ws-group-header" @click="$emit('toggle-group', group.id)">
          <span class="ws-group-caret">{{ collapsed[group.id] ? '▸' : '▾' }}</span>
          <n-icon class="ws-group-icon"><FolderOpenOutline /></n-icon>
          <span class="ws-group-name" :title="group.boundPath || group.name">{{ group.name }}</span>
          <n-button
            quaternary
            circle
            size="tiny"
            title="在此工作区新建会话"
            @click.stop="$emit('new-in-workspace', group)"
          >
            <template #icon><n-icon><AddOutline /></n-icon></template>
          </n-button>
        </div>
        <div v-show="!collapsed[group.id]">
          <div v-if="!(group.conversations && group.conversations.length)" class="ws-group-empty">暂无会话</div>
          <div
            v-for="conv in group.conversations"
            :key="conv.id"
            class="conversation-item"
            :class="{ active: currentConversationId === conv.id }"
            @click="$emit('select', conv.id)"
          >
            <n-input
              v-if="editingConvId === conv.id"
              :value="editingConvTitle"
              size="tiny"
              @update:value="$emit('update:editingConvTitle', $event)"
              @click.stop
              @keyup.enter="$emit('rename-confirm', conv)"
              @blur="$emit('rename-confirm', conv)"
            />
            <span v-else class="conv-title" :title="conv.title" @dblclick.stop="$emit('rename-start', conv)">
              <span v-if="busyIds[conv.id]" class="conv-busy" title="正在回复">●</span>
              {{ conv.title }}
            </span>
            <n-button v-if="editingConvId !== conv.id" quaternary circle size="tiny" @click.stop="$emit('rename-start', conv)" title="重命名">
              <template #icon><n-icon><CreateOutline /></n-icon></template>
            </n-button>
            <n-button quaternary circle size="tiny" @click.stop="$emit('delete', conv.id)" title="删除会话">
              <template #icon><n-icon><TrashOutline /></n-icon></template>
            </n-button>
          </div>
        </div>
      </div>

      <div class="ws-group" data-ws-name="默认工作区">
        <div class="ws-group-header" @click="$emit('toggle-group', 'default')">
          <span class="ws-group-caret">{{ collapsed.default ? '▸' : '▾' }}</span>
          <n-icon class="ws-group-icon"><FolderOpenOutline /></n-icon>
          <span class="ws-group-name">默认工作区</span>
        </div>
        <div v-show="!collapsed.default">
          <div v-if="!defaultConversations.length" class="ws-group-empty">暂无未绑定会话</div>
          <div
            v-for="conv in defaultConversations"
            :key="conv.id"
            class="conversation-item"
            :class="{ active: currentConversationId === conv.id }"
            @click="$emit('select', conv.id)"
          >
            <n-input
              v-if="editingConvId === conv.id"
              :value="editingConvTitle"
              size="tiny"
              @update:value="$emit('update:editingConvTitle', $event)"
              @click.stop
              @keyup.enter="$emit('rename-confirm', conv)"
              @blur="$emit('rename-confirm', conv)"
            />
            <span v-else class="conv-title" :title="conv.title" @dblclick.stop="$emit('rename-start', conv)">
              <span v-if="busyIds[conv.id]" class="conv-busy" title="正在回复">●</span>
              {{ conv.title }}
            </span>
            <n-button v-if="editingConvId !== conv.id" quaternary circle size="tiny" @click.stop="$emit('rename-start', conv)" title="重命名">
              <template #icon><n-icon><CreateOutline /></n-icon></template>
            </n-button>
            <n-button quaternary circle size="tiny" @click.stop="$emit('delete', conv.id)" title="删除会话">
              <template #icon><n-icon><TrashOutline /></n-icon></template>
            </n-button>
          </div>
        </div>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed } from 'vue'
import { NButton, NInput, NIcon } from 'naive-ui'
import { AddOutline, TrashOutline, CreateOutline, FolderOpenOutline } from '@vicons/ionicons5'
import { useRoute, useRouter } from 'vue-router'

const route = useRoute()
const router = useRouter()
const isMcpRoute = computed(() => route.path === '/mcp')
const goMcp = () => {
  if (route.path !== '/mcp') router.push('/mcp')
}

defineProps({
  sidebarOpen: { type: Boolean, default: false },
  groups: { type: Array, default: () => [] },
  defaultConversations: { type: Array, default: () => [] },
  currentConversationId: { type: String, default: null },
  collapsed: { type: Object, default: () => ({}) },
  busyIds: { type: Object, default: () => ({}) },
  editingConvId: { type: String, default: null },
  editingConvTitle: { type: String, default: '' }
})

defineEmits([
  'new-unbound',
  'new-in-workspace',
  'select',
  'delete',
  'rename-start',
  'rename-confirm',
  'update:editingConvTitle',
  'toggle-group'
])
</script>

<style scoped>
.sidebar {
  width: 280px;
  min-width: 280px;
  background: rgba(255, 245, 220, 0.85);
  backdrop-filter: blur(20px);
  border-right: 1px solid rgba(224, 165, 107, 0.3);
  display: flex;
  flex-direction: column;
  z-index: 2;
  height: 100vh;
}
.sidebar-header { padding: 20px 16px; border-bottom: 1px solid rgba(224, 165, 107, 0.3); }
.mcp-entry { margin-top: 8px; color: #8a5a32; }
.mcp-entry.active {
  background: rgba(255, 216, 154, 0.65);
  color: #7a4c2c;
  font-weight: 600;
}
.conversation-list { flex: 1; overflow-y: auto; overflow-x: hidden; padding: 12px; }
.ws-group { margin-bottom: 10px; }
.ws-group-header {
  display: flex; align-items: center; gap: 4px;
  min-height: 28px;
  padding: 4px 6px; border-radius: 8px; cursor: pointer;
  color: #8a5a32; font-size: 0.82rem; font-weight: 600;
}
.ws-group-header:hover { background: rgba(255, 216, 154, 0.35); }
.ws-group-caret { width: 12px; flex-shrink: 0; font-size: 0.7rem; line-height: 1; }
.ws-group-icon { color: #c99a54; flex-shrink: 0; }
.ws-group-name {
  flex: 1; min-width: 0;
  overflow: hidden; text-overflow: ellipsis; white-space: nowrap;
  line-height: 1.4;
}
.ws-group-empty { font-size: 0.75rem; color: #b9a488; padding: 6px 12px 8px 28px; }
.conversation-item {
  display: flex; justify-content: space-between; align-items: center;
  padding: 8px 10px 8px 22px; margin-bottom: 6px; border-radius: 12px; cursor: pointer;
  background: rgba(255, 255, 255, 0.5); transition: all 0.2s;
}
.conversation-item:hover { background: rgba(255, 216, 154, 0.5); }
.conversation-item.active { background: #ffd89a; box-shadow: 0 2px 6px rgba(0,0,0,0.05); }
.conv-title { font-size: 0.9rem; font-weight: 500; color: #7a4c2c; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; flex: 1; min-width: 0; display: flex; align-items: center; gap: 6px; }
.conv-busy { color: #e89a3c; font-size: 0.7rem; flex-shrink: 0; animation: conv-busy-pulse 1.2s ease-in-out infinite; }
@keyframes conv-busy-pulse { 0%, 100% { opacity: 1; } 50% { opacity: 0.3; } }
</style>
