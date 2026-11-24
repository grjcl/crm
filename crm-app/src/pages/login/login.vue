<template>
  <view class="login-page">
    <view class="login-head">
      <view class="logo">商</view>
      <view class="title">商旅分销 CRM</view>
      <view class="subtitle">内部使用 · 上下游客户与供应商管理</view>
    </view>

    <view class="login-body">
      <view class="field">
        <text class="field-label">手机号</text>
        <input
          v-model="phone"
          class="field-input"
          type="number"
          maxlength="11"
          placeholder="请输入手机号"
          placeholder-class="field-placeholder"
        />
      </view>

      <view class="field">
        <text class="field-label">密码</text>
        <view class="pwd-row">
          <input
            v-model="password"
            class="field-input"
            :password="!showPassword"
            maxlength="20"
            placeholder="请输入密码"
            placeholder-class="field-placeholder"
          />
          <text class="pwd-toggle" @click="showPassword = !showPassword">
            {{ showPassword ? '隐藏' : '显示' }}
          </text>
        </view>
      </view>

      <button class="login-btn" :disabled="loading" @click="handleLogin">
        {{ loading ? '登录中…' : '登 录' }}
      </button>

      <view class="links">
        <text class="forgot" @click="handleForgot">忘记密码？</text>
        <text class="hint">未开通账号请联系管理员</text>
      </view>

      <view class="foot">
        <view>本期仅支持手机号 + 密码登录</view>
        <view>密码错误 5 次锁定 15 分钟</view>
      </view>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { onLoad } from '@dcloudio/uni-app'
import http, { ApiError } from '../../utils/request'

const PHONE_PATTERN = /^1[3-9]\d{9}$/

const ERROR_MESSAGES: Record<number, string> = {
  2001: '手机号未开通，请联系管理员',
  2002: '手机号或密码错误',
  2003: '密码错误次数过多，请 15 分钟后再试或联系管理员'
}

const phone = ref('')
const password = ref('')
const showPassword = ref(false)
const loading = ref(false)

function toast(title: string): void {
  uni.showToast({ title, icon: 'none', duration: 2500 })
}

onLoad(() => {
  if (uni.getStorageSync('refreshToken')) {
    uni.reLaunch({ url: '/pages/partners/partners' })
  }
})

async function handleLogin(): Promise<void> {
  if (loading.value) {
    return
  }
  if (!PHONE_PATTERN.test(phone.value)) {
    toast('请输入正确的手机号')
    return
  }
  if (!password.value) {
    toast('请输入密码')
    return
  }

  loading.value = true
  try {
    const response: any = await http.post('/auth/login', {
      phone: phone.value,
      password: password.value
    })
    const data = response?.data
    if (!data?.accessToken) {
      toast('登录失败，请稍后重试')
      return
    }
    uni.setStorageSync('accessToken', data.accessToken)
    uni.setStorageSync('refreshToken', data.refreshToken)
    uni.reLaunch({ url: '/pages/partners/partners' })
  } catch (error) {
    const apiError = error as ApiError
    const message = ERROR_MESSAGES[apiError.code] || apiError.message || '登录失败，请稍后重试'
    toast(message)
  } finally {
    loading.value = false
  }
}

function handleForgot(): void {
  toast('请联系管理员重置密码')
}
</script>

<style scoped>
.login-page {
  box-sizing: border-box;
  min-height: 100vh;
  background: #f2f4f8;
  padding-top: calc(52px + var(--status-bar-height, 0px));
}

.login-head {
  padding: 0 26px;
}

.logo {
  width: 58px;
  height: 58px;
  border-radius: 17px;
  background: linear-gradient(135deg, #22385f, #2c4c86);
  display: flex;
  align-items: center;
  justify-content: center;
  color: #ffffff;
  font-size: 22px;
  font-weight: 700;
  box-shadow: 0 12px 26px -12px rgba(31, 56, 100, 0.7);
}

.title {
  margin-top: 20px;
  font-size: 23px;
  font-weight: 800;
  letter-spacing: 0.01em;
  color: #161a22;
}

.subtitle {
  margin-top: 7px;
  font-size: 11.5px;
  color: #9aa3af;
  line-height: 1.55;
}

.login-body {
  padding: 30px 26px 0;
}

.field {
  padding: 11px 0;
  border-bottom: 1px solid #f1f3f7;
}

.field-label {
  display: block;
  font-size: 12px;
  color: #8a94a6;
  margin-bottom: 6px;
}

.field-input {
  width: 100%;
  height: 24px;
  border: 0;
  outline: none;
  font-size: 14.5px;
  line-height: 1.55;
  color: #1f2937;
  background: transparent;
}

.field-placeholder {
  color: #a6aebc;
}

.pwd-row {
  display: flex;
  align-items: center;
}

.pwd-row .field-input {
  flex: 1;
  min-width: 0;
}

.pwd-toggle {
  flex: none;
  padding-left: 10px;
  font-size: 12px;
  color: #2c4c86;
}

.login-btn {
  width: 100%;
  height: 45px;
  margin-top: 22px;
  border-radius: 13px;
  border: 0;
  background: #1f3864;
  color: #ffffff;
  font-size: 15px;
  font-weight: 600;
  line-height: 45px;
}

.login-btn::after {
  border: none;
}

.login-btn[disabled] {
  opacity: 0.65;
  color: #ffffff;
  background: #1f3864;
}

.links {
  display: flex;
  justify-content: space-between;
  margin-top: 14px;
}

.forgot {
  font-size: 11.5px;
  line-height: 1.55;
  color: #2c4c86;
  font-weight: 600;
}

.hint {
  font-size: 11.5px;
  line-height: 1.55;
  color: #9aa3af;
}

.foot {
  margin-top: 34px;
  text-align: center;
  font-size: 11.5px;
  line-height: 1.7;
  color: #9aa3af;
}
</style>
