import axios from 'axios'
import { ElMessage } from 'element-plus'

const http = axios.create({ baseURL: '/api', timeout: 60000 })

http.interceptors.request.use((cfg) => {
  const token = localStorage.getItem('bovin_token')
  if (token) cfg.headers.Authorization = `Bearer ${token}`
  return cfg
})

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body.code === 'number' && body.code !== 0) {
      if (body.code === 401) {
        localStorage.removeItem('bovin_token')
        if (!location.pathname.startsWith('/login')) location.href = '/login'
      }
      ElMessage.error(body.message || '请求失败')
      return Promise.reject(new Error(body.message))
    }
    return body.data ?? body
  },
  (err) => {
    const status = err.response?.status
    if (status === 401) {
      localStorage.removeItem('bovin_token')
      if (!location.pathname.startsWith('/login')) location.href = '/login'
    }
    ElMessage.error(err.response?.data?.message || '网络错误,请稍后重试')
    return Promise.reject(err)
  }
)

export default http
