import axios from 'axios'

const request = axios.create({
    baseURL: '',
    timeout: 30000,
    headers: {
        'Content-Type': 'application/json'
    }
})

// 请求拦截器（自动携带token）
request.interceptors.request.use(config => {
    const token = localStorage.getItem("token")
    if (token) {
        config.headers.Authorization = `Bearer ${token}`
    }
    return config
})

// 响应拦截器（统一处理响应）
request.interceptors.response.use(
    response => {
        // 直接返回 response.data（后端返回的 JSON 数据）
        return response.data
    },
    error => {
        console.error('请求错误:', error)
        
        // 处理 401 未授权
        // 本机模式（后端 app.auth.mode=local）不会因为缺 token 返回 401；
        // 真出现 401 就是真实的业务/服务端错误，交给调用方展示，
        // 不再清空本地状态、也不再强制跳转登录页。
        // 将来恢复多用户时，把下面的清理与跳转加回来即可。
        if (error.response?.status === 401) {
            console.warn('401 未授权:', error.response?.data || error.message)
        }
        
        return Promise.reject(error)
    }
)

export default request