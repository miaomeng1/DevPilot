import { createPinia } from 'pinia'
import { createApp } from 'vue'
import '@xterm/xterm/css/xterm.css'

import App from './App.vue'
import router from './router'
import './styles/index.css'

createApp(App).use(createPinia()).use(router).mount('#app')
