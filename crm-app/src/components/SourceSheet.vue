<template>
  <view v-if="modelValue" class="source-mask" @click="close">
    <view class="source-sheet" @click.stop>
      <view class="sheet-head">
        <view class="sheet-title">来源字典</view>
        <text class="sheet-close" @click="close">×</text>
      </view>
      <view class="sheet-desc">发现登记的来源选项，可增删改；改名会同步到已有发现记录</view>

      <view v-if="loading" class="sheet-state">加载中…</view>
      <view v-else-if="sources.length === 0" class="sheet-state">暂无来源，先添加一个吧</view>
      <view v-else class="source-list">
        <view v-for="item in sources" :key="item.id" class="source-row">
          <view class="row-main">
            <input
              v-model="drafts[item.id]"
              class="name-input"
              :maxlength="MAX_NAME_LENGTH"
              :disabled="busy"
              @blur="rename(item)"
            />
            <text v-if="typeof item.refCount === 'number'" class="ref-count">
              {{ item.refCount }} 条发现
            </text>
          </view>
          <button class="del" :disabled="busy" @click.stop="remove(item)">×</button>
        </view>
      </view>

      <view class="add-row">
        <input
          v-model="newName"
          class="name-input"
          :maxlength="MAX_NAME_LENGTH"
          placeholder="来源名称"
          placeholder-class="name-placeholder"
          :disabled="busy"
          @confirm="add"
        />
        <button class="add-btn" :disabled="busy" @click.stop="add">添加</button>
      </view>

      <button class="done-btn" :disabled="busy" @click="close">完成</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../utils/request'
import {
  createSource,
  deleteSource,
  listSources,
  updateSource,
  type LeadSource
} from '../api/leadSource'

/** 与 shared/db 的 lead_source.name varchar(10) / R-VAL-28 对齐 */
const MAX_NAME_LENGTH = 10

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'changed'): void
}>()

const sources = ref<LeadSource[]>([])
const drafts = ref<Record<number, string>>({})
const newName = ref('')
const loading = ref(false)
const busy = ref(false)

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void load()
    }
  },
  { immediate: true }
)

async function load(): Promise<void> {
  loading.value = true
  try {
    const list = await listSources()
    sources.value = list
    drafts.value = list.reduce<Record<number, string>>((result, item) => {
      result[item.id] = item.name
      return result
    }, {})
  } catch (error) {
    toast(errorMessage(error))
  } finally {
    loading.value = false
  }
}

async function add(): Promise<void> {
  const name = newName.value.trim()
  if (!name) {
    toast('请输入来源名')
    return
  }
  if (name.length > MAX_NAME_LENGTH) {
    toast(`来源名称不能超过 ${MAX_NAME_LENGTH} 字`)
    return
  }
  if (sources.value.some((item) => item.name === name)) {
    toast('来源已存在')
    return
  }
  if (busy.value) {
    return
  }

  busy.value = true
  try {
    const created = await createSource(name)
    sources.value = [...sources.value, { id: created.id, name: created.name, sort: created.sort }]
    drafts.value = { ...drafts.value, [created.id]: created.name }
    newName.value = ''
    emit('changed')
    toast(`已添加来源「${created.name}」`)
  } catch (error) {
    toast(errorMessage(error))
  } finally {
    busy.value = false
  }
}

async function rename(item: LeadSource): Promise<void> {
  const name = (drafts.value[item.id] ?? '').trim()
  if (!name) {
    drafts.value[item.id] = item.name
    toast('请输入来源名')
    return
  }
  if (name === item.name) {
    drafts.value[item.id] = name
    return
  }
  if (name.length > MAX_NAME_LENGTH) {
    drafts.value[item.id] = item.name
    toast(`来源名称不能超过 ${MAX_NAME_LENGTH} 字`)
    return
  }
  if (busy.value) {
    drafts.value[item.id] = item.name
    return
  }

  busy.value = true
  try {
    await updateSource(item.id, name)
    item.name = name
    drafts.value[item.id] = name
    emit('changed')
    toast(`已重命名为「${name}」`)
  } catch (error) {
    drafts.value[item.id] = item.name
    toast(errorMessage(error))
  } finally {
    busy.value = false
  }
}

async function remove(item: LeadSource): Promise<void> {
  if (busy.value) {
    return
  }
  if (sources.value.length <= 1) {
    toast('至少保留一个来源')
    return
  }
  if (typeof item.refCount === 'number' && item.refCount > 0) {
    toast(`该来源下还有 ${item.refCount} 条发现记录，请先处理`)
    return
  }

  const confirmed = await confirmDelete(item)
  if (!confirmed) {
    return
  }

  busy.value = true
  try {
    await deleteSource(item.id)
    sources.value = sources.value.filter((source) => source.id !== item.id)
    emit('changed')
    toast('已删除')
  } catch (error) {
    toast(errorMessage(error))
  } finally {
    busy.value = false
  }
}

function confirmDelete(item: LeadSource): Promise<boolean> {
  return new Promise((resolve) => {
    uni.showModal({
      title: '删除来源',
      content: `确定删除来源「${item.name}」？`,
      confirmText: '删除',
      confirmColor: '#C0392B',
      success: (result) => resolve(Boolean(result.confirm)),
      fail: () => resolve(false)
    })
  })
}

function close(): void {
  emit('update:modelValue', false)
}

function errorMessage(error: unknown): string {
  return error instanceof ApiError ? error.message : '网络异常，请稍后重试'
}

function toast(title: string): void {
  uni.showToast({ title, icon: 'none' })
}
</script>

<style scoped>
.source-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: flex-end;
  background: rgba(16, 22, 34, 0.42);
}

.source-sheet {
  box-sizing: border-box;
  width: 100%;
  max-height: 88%;
  overflow-y: auto;
  padding: 16px 16px 22px;
  border-radius: 20px 20px 0 0;
  background: #ffffff;
  animation: source-sheet-up 0.22s ease;
}

@keyframes source-sheet-up {
  from {
    transform: translateY(20px);
    opacity: 0.6;
  }
  to {
    transform: none;
    opacity: 1;
  }
}

.sheet-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.sheet-title {
  font-size: 15.5px;
  font-weight: 700;
  color: #161a22;
}

.sheet-close {
  width: 30px;
  height: 30px;
  margin-top: -6px;
  border-radius: 9px;
  background: #f5f6fa;
  color: #9aa3af;
  font-size: 18px;
  line-height: 30px;
  text-align: center;
}

.sheet-desc {
  margin: 4px 0 12px;
  font-size: 12px;
  line-height: 1.5;
  color: #8a94a6;
}

.sheet-state {
  padding: 18px 0;
  font-size: 13px;
  color: #8a94a6;
  text-align: center;
}

.source-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid #f2f4f8;
}

.row-main {
  display: flex;
  flex: 1;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.name-input {
  flex: 1;
  min-width: 0;
  height: 24px;
  padding: 4px 0;
  border: 0;
  outline: none;
  background: transparent;
  color: #1f2937;
  font-size: 15px;
  line-height: 1.5;
}

.name-placeholder {
  color: #a6aebc;
}

.ref-count {
  flex: none;
  font-size: 11.5px;
  color: #8a94a6;
}

.del {
  flex: none;
  width: 30px;
  height: 30px;
  padding: 0;
  border: 0;
  border-radius: 9px;
  background: #f5f6fa;
  color: #9aa3af;
  font-size: 17px;
  line-height: 1;
}

.del:active {
  background: #fce9e7;
  color: #c0392b;
}

.add-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0 0;
}

.add-btn {
  flex: none;
  height: 36px;
  padding: 0 14px;
  border: 0;
  border-radius: 10px;
  background: #1f3864;
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  line-height: 36px;
}

.done-btn {
  width: 100%;
  height: 45px;
  margin-top: 14px;
  border: 0;
  border-radius: 13px;
  background: #ffffff;
  color: #1f3864;
  font-size: 15px;
  font-weight: 600;
  line-height: 45px;
  box-shadow: 0 6px 18px -10px rgba(16, 24, 40, 0.28);
}

button::after {
  border: none;
}
</style>
