<template>
  <view v-if="modelValue" class="tag-mask" @click="close">
    <view class="tag-sheet" @click.stop>
      <view class="sheet-head">
        <view class="sheet-title">标签管理</view>
        <text class="sheet-close" @click.stop="close">×</text>
      </view>
      <view class="sheet-desc">默认三个，可增删改；列表按标签筛选，一家可打多个标签</view>

      <view v-if="loading" class="sheet-state">加载中…</view>
      <view v-else-if="tags.length === 0" class="sheet-state">暂无标签，先添加一个吧</view>
      <view v-else class="tag-list">
        <view v-for="item in tags" :key="item.id" class="tag-row">
          <view class="row-main">
            <view class="name-line">
              <input
                v-if="editingId === item.id"
                v-model="editName"
                class="name-input"
                :maxlength="MAX_NAME_LENGTH"
                :focus="editingId === item.id"
                :disabled="busy"
                @blur="saveRename(item)"
                @confirm="saveRename(item)"
              />
              <text v-else class="name">{{ item.name }}</text>
              <text class="status" :class="{ off: !item.enabled }">
                {{ item.enabled ? '启用' : '停用' }}
              </text>
            </view>
            <text class="meta">{{ item.orgCount }} 家合作商</text>
          </view>
          <view class="row-actions">
            <button
              v-if="editingId !== item.id"
              class="act"
              :disabled="busy"
              @click.stop="startEdit(item)"
            >
              编辑
            </button>
            <button v-else class="act" :disabled="busy" @click.stop="saveRename(item)">保存</button>
            <button class="del" :disabled="busy" @click.stop="remove(item)">删除</button>
          </view>
        </view>
      </view>

      <view class="add-row">
        <input
          v-model="newName"
          class="name-input"
          :maxlength="MAX_NAME_LENGTH"
          placeholder="+ 新建标签"
          placeholder-class="name-placeholder"
          :disabled="busy"
          @confirm="add"
        />
        <button class="add-btn" :disabled="busy" @click.stop="add">保存</button>
      </view>

      <button class="done-btn" :disabled="busy" @click.stop="close">完成</button>
    </view>
  </view>
</template>

<script setup lang="ts">
import { ref, watch } from 'vue'
import { ApiError } from '../utils/request'
import { createTag, deleteTag, listTags, updateTag, type Tag } from '../api/tag'

/** 与 shared/db 的 org_tag.name varchar(10) 对齐。 */
const MAX_NAME_LENGTH = 10

const props = defineProps<{ modelValue: boolean }>()
const emit = defineEmits<{
  (event: 'update:modelValue', value: boolean): void
  (event: 'changed'): void
}>()

const tags = ref<Tag[]>([])
const loading = ref(false)
const busy = ref(false)
const newName = ref('')
const editingId = ref<number | null>(null)
const editName = ref('')

watch(
  () => props.modelValue,
  (visible) => {
    if (!visible) {
      editingId.value = null
      return
    }
    newName.value = ''
    editingId.value = null
    void load()
  },
  { immediate: true }
)

function toast(title: string): void {
  uni.showToast({ title, icon: 'none', duration: 2200 })
}

function messageOf(error: unknown, fallback: string): string {
  const apiError = error as ApiError
  return apiError?.message || fallback
}

async function load(): Promise<void> {
  loading.value = true
  try {
    tags.value = await listTags()
  } catch (error) {
    toast(messageOf(error, '标签加载失败'))
  } finally {
    loading.value = false
  }
}

function close(): void {
  if (busy.value) {
    return
  }
  editingId.value = null
  emit('update:modelValue', false)
}

function startEdit(item: Tag): void {
  if (busy.value) {
    return
  }
  editingId.value = item.id
  editName.value = item.name
}

async function saveRename(item: Tag): Promise<void> {
  if (busy.value || editingId.value !== item.id) {
    return
  }

  const name = editName.value.trim()
  if (!name || name.length > MAX_NAME_LENGTH) {
    toast(!name ? '请输入标签名称' : `标签名称不能超过 ${MAX_NAME_LENGTH} 字`)
    editName.value = item.name
    editingId.value = null
    return
  }
  if (name === item.name) {
    editingId.value = null
    return
  }

  busy.value = true
  try {
    const renamed = await updateTag(item.id, name)
    item.name = renamed.name
    editingId.value = null
    toast('已重命名')
    emit('changed')
  } catch (error) {
    const apiError = error as ApiError
    editName.value = item.name
    editingId.value = null
    toast(apiError?.code === 3001 ? '标签名重复' : messageOf(error, '改名失败'))
  } finally {
    busy.value = false
  }
}

async function add(): Promise<void> {
  if (busy.value) {
    return
  }

  const name = newName.value.trim()
  if (!name) {
    toast('请输入标签名称')
    return
  }
  if (name.length > MAX_NAME_LENGTH) {
    toast(`标签名称不能超过 ${MAX_NAME_LENGTH} 字`)
    return
  }
  if (tags.value.some((tag) => tag.name === name)) {
    toast('标签名重复')
    return
  }

  busy.value = true
  try {
    const created = await createTag(name)
    tags.value.push({ ...created, enabled: true, orgCount: 0 })
    newName.value = ''
    toast('已添加标签')
    emit('changed')
  } catch (error) {
    const apiError = error as ApiError
    toast(apiError?.code === 3001 ? '标签名重复' : messageOf(error, '添加失败'))
  } finally {
    busy.value = false
  }
}

function remove(item: Tag): void {
  if (busy.value) {
    return
  }

  if (item.orgCount > 0) {
    uni.showModal({
      title: '无法删除',
      content: `该标签下还有 ${item.orgCount} 家合作商，请先移除`,
      showCancel: false,
      confirmText: '知道了'
    })
    return
  }

  if (tags.value.length <= 1) {
    toast('至少保留一个标签')
    return
  }

  uni.showModal({
    title: '删除标签',
    content: `确定删除标签「${item.name}」？`,
    confirmText: '确定',
    cancelText: '取消',
    success: (result) => {
      if (result.confirm) {
        void doRemove(item)
      }
    }
  })
}

async function doRemove(item: Tag): Promise<void> {
  busy.value = true
  try {
    await deleteTag(item.id)
    tags.value = tags.value.filter((tag) => tag.id !== item.id)
    toast('已删除标签')
    emit('changed')
  } catch (error) {
    toast(messageOf(error, '删除失败'))
  } finally {
    busy.value = false
  }
}
</script>

<style scoped>
.tag-mask {
  position: fixed;
  inset: 0;
  z-index: 100;
  display: flex;
  align-items: flex-end;
  background: rgba(16, 22, 34, 0.42);
}

.tag-sheet {
  box-sizing: border-box;
  width: 100%;
  max-height: 88vh;
  padding: 16px 16px calc(22px + env(safe-area-inset-bottom));
  background: #ffffff;
  border-radius: 20px 20px 0 0;
  animation: tag-up 0.2s ease;
}

@keyframes tag-up {
  from {
    transform: translateY(100%);
  }
  to {
    transform: translateY(0);
  }
}

.sheet-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.sheet-title {
  font-size: 15.5px;
  font-weight: 700;
  color: #161a22;
}

.sheet-close {
  padding: 2px 4px;
  font-size: 22px;
  line-height: 1;
  color: #9aa3af;
}

.sheet-desc {
  margin: 4px 0 12px;
  font-size: 12px;
  line-height: 1.55;
  color: #8a94a6;
}

.sheet-state {
  padding: 28px 0;
  text-align: center;
  font-size: 13px;
  color: #9aa3af;
}

.tag-list {
  max-height: 52vh;
  overflow-y: auto;
}

.tag-row {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 0;
  border-bottom: 1px solid #f2f4f8;
}

.row-main {
  flex: 1;
  min-width: 0;
}

.name-line {
  display: flex;
  align-items: center;
  gap: 7px;
  min-width: 0;
}

.name,
.name-input {
  flex: 1;
  min-width: 0;
  font-size: 15px;
  font-family: inherit;
  color: #1f2937;
}

.name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.name-input {
  height: 26px;
  padding: 2px 0;
  border: 0;
  outline: none;
  background: transparent;
}

.name-placeholder {
  color: #a6aebc;
}

.status {
  flex: none;
  padding: 1px 7px;
  border-radius: 6px;
  background: #edf1f9;
  color: #3a5078;
  font-size: 10px;
  line-height: 16px;
}

.status.off {
  background: #f3f4f6;
  color: #9aa3af;
}

.meta {
  display: block;
  margin-top: 2px;
  font-size: 11.5px;
  color: #8a94a6;
}

.row-actions {
  flex: none;
  display: flex;
  align-items: center;
  gap: 6px;
}

.act,
.del,
.add-btn,
.done-btn {
  box-sizing: border-box;
  margin: 0;
  padding: 0;
  border: 0;
  font-family: inherit;
  line-height: 1;
}

.act::after,
.del::after,
.add-btn::after,
.done-btn::after {
  border: none;
}

.act {
  height: 30px;
  padding: 0 10px;
  border-radius: 9px;
  background: #edf1f9;
  color: #3a5078;
  font-size: 12.5px;
  line-height: 30px;
}

.del {
  height: 30px;
  padding: 0 10px;
  border-radius: 9px;
  background: #f5f6fa;
  color: #9aa3af;
  font-size: 12.5px;
  line-height: 30px;
}

.del:active {
  background: #fce9e7;
  color: #c0392b;
}

.add-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-top: 8px;
  padding: 6px 0;
  border-bottom: 1px solid #f2f4f8;
}

.add-row .name-input {
  flex: 1;
}

.add-btn {
  flex: none;
  height: 34px;
  padding: 0 16px;
  border-radius: 13px;
  background: #1f3864;
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  line-height: 34px;
}

.add-btn[disabled],
.act[disabled],
.del[disabled],
.done-btn[disabled] {
  opacity: 0.55;
}

.done-btn {
  width: 100%;
  height: 40px;
  margin-top: 14px;
  border: 1px solid #e2e7f0;
  border-radius: 13px;
  background: #ffffff;
  color: #55637c;
  font-size: 14px;
  line-height: 38px;
}
</style>
