import Request from 'luch-request'

const DEFAULT_BASE_URL = 'http://localhost:8080/api/v1'
const LOGIN_PAGE = '/pages/login/login'

export class ApiError extends Error {
  code: number

  constructor(code: number, message: string) {
    super(message)
    this.name = 'ApiError'
    this.code = code
  }
}

function baseURL(): string {
  return import.meta.env.VITE_API_BASE || DEFAULT_BASE_URL
}

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

function clearTokens(): void {
  uni.removeStorageSync('accessToken')
  uni.removeStorageSync('refreshToken')
}

function redirectToLogin(): void {
  clearTokens()
  const pages = typeof getCurrentPages === 'function' ? getCurrentPages() : []
  const current = pages.length > 0 ? pages[pages.length - 1].route : ''
  if (current === 'pages/login/login') {
    return
  }
  uni.reLaunch({ url: LOGIN_PAGE })
}

let refreshPromise: Promise<boolean> | null = null

/**
 * 用 refreshToken 换新 token。直接使用 uni.request，绕开拦截器，
 * 保证 refresh 自身失败时不会再触发 refresh。
 */
function refreshTokens(): Promise<boolean> {
  if (refreshPromise) {
    return refreshPromise
  }

  const refreshToken = uni.getStorageSync('refreshToken')
  if (!refreshToken) {
    return Promise.resolve(false)
  }

  refreshPromise = new Promise<boolean>((resolve) => {
    uni.request({
      url: `${baseURL()}/auth/refresh`,
      method: 'POST',
      header: { 'Content-Type': 'application/json' },
      data: { refreshToken },
      timeout: 15000,
      success: (response) => {
        const body = response.data as any
        if (response.statusCode === 200 && body?.code === 0 && body?.data?.accessToken) {
          uni.setStorageSync('accessToken', body.data.accessToken)
          uni.setStorageSync('refreshToken', body.data.refreshToken)
          resolve(true)
          return
        }
        resolve(false)
      },
      fail: () => resolve(false)
    })
  }).finally(() => {
    refreshPromise = null
  })

  return refreshPromise
}

async function handleUnauthorized(config: any, message?: string): Promise<any> {
  if (!config?.__retried && uni.getStorageSync('refreshToken')) {
    const refreshed = await refreshTokens()
    if (refreshed) {
      return http.request({ ...config, __retried: true })
    }
  }
  redirectToLogin()
  throw new ApiError(1002, message || '登录已过期，请重新登录')
}

const http = new Request()

http.setConfig((config: any) => {
  config.baseURL = baseURL()
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
  if (['POST', 'PUT', 'DELETE'].includes(method) && !config.header['Idempotency-Key']) {
    config.header['Idempotency-Key'] = uuidv4()
  }

  return config
})

http.interceptors.response.use(
  (response: any) => {
    const body = response?.data
    const config = response?.config || {}

    if (response?.statusCode === 401 || body?.code === 1002) {
      return handleUnauthorized(config, body?.message)
    }

    if (body && typeof body === 'object' && 'code' in body && body.code !== 0) {
      return Promise.reject(new ApiError(body.code, body.message || '请求失败'))
    }

    return body
  },
  (error: any) => {
    const statusCode = error?.statusCode ?? error?.response?.statusCode
    const body = error?.data ?? error?.response?.data

    if (statusCode === 401 || body?.code === 1002) {
      return handleUnauthorized(error?.config, body?.message)
    }

    if (body && typeof body === 'object' && typeof body.code === 'number' && body.code !== 0) {
      return Promise.reject(new ApiError(body.code, body.message || '请求失败'))
    }

    return Promise.reject(new ApiError(statusCode || -1, body?.message || error?.errMsg || '网络异常，请稍后重试'))
  }
)

export default http
