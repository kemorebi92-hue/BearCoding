import { createRouter, createWebHistory } from 'vue-router'

import Chat from '@/views/ChatView.vue'
import ModelSettings from '@/views/ModelSettings.vue'

const routes = [
    { path: '/', redirect: '/chat' },
    { path: '/login', redirect: '/chat' },
    { path: '/register', redirect: '/chat' },
    { path: '/chat', component: Chat },
    { path: '/models', component: ModelSettings },
    { path: '/mcp', component: Chat }
]

const router = createRouter({
    history: createWebHistory(),
    routes
})

export default router
