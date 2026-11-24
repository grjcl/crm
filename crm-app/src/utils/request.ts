import Request from 'luch-request'

const DEFAULT_BASE_URL = 'http://localhost:8080/api/v1'

function uuidv4(): string {
  const cryptoObj = (globalThis as { crypto?: { randomUUID?: () => string } }).crypto
  if (cryptoObj?.randomUUID) {
    return cryptoObj.randomUUID()
  }

  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, (char) => {
    const random = (Math.random() * 16) | 0
    const value = char === 'x' ? random : (random & 0x3) | 0x8
    return value.toString(16)
  })
}

function redirectToLogin(): void {
  uni.removeStorageSync('accessToken')
  uni.removeStorageSync('refreshToken')
  uni.reLaunch({ url: '/pages/login/login' })
}

const http = new Request()

http.setConfig((config: any) => {
  config.baseURL = import.meta.env.VITE_API_BASE || DEFAULT_BASE_URL
  config.timeout = 15000
  return config
})

http.interceptors.request.use((config: any) => {
  config.header = config.header || {}

  const token = uni.getStorageSync('accessToken')
  if (token) {
    config.header.Authorization = `Bearer ${token}`
  }

  const method = (config.method || 'GET').toUpperCase()
  if (['POST', 'PUT', 'DELETE'].includes(method)) {
    config.header['Idempotency-Key'] = uuidv4()
  }

  return config
})

http.interceptors.response.use(
  (response: any) => {
    const body = response?.data

    if (response?.statusCode === 401 || body?.code === 401) {
      redirectToLogin()
      return Promise.reject(new Error(body?.message || '登录已过期'))
    }

    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      return Promise.reject(new Error(body.message || '请求失败'))
    }

    return body
  },
  (error: any) => {
    const statusCode = error?.statusCode ?? error?.response?.statusCode
    if (statusCode === 401) {
      redirectToLogin()
    }
    return Promise.reject(error)
  }
)

export default http
