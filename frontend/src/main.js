import { createApp } from 'vue'
import App from './App.vue'
import router from './router'
import naive from 'naive-ui'

// 引入字体（可选，让文字更好看）
import 'vfonts/Lato.css'
import 'vfonts/FiraCode.css'

const app = createApp(App)
app.use(router)
app.use(naive)
app.mount('#app')