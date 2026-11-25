<template>
  <view class="partners">
    <view class="nav">
      <template v-if="!searching">
        <text class="nav-title">合作商</text>
        <view class="nav-actions"><button class="icon" aria-label="搜索" @click="searching = true">⌕</button><button class="icon plus" aria-label="新建合作商" @click="openNew">＋</button></view>
      </template>
      <template v-else>
        <view class="search-box"><text>⌕</text><input :value="query" focus placeholder="搜索合作商名称" @input="onInput" /></view>
        <button class="cancel" @click="cancelSearch">取消</button>
      </template>
    </view>
    <scroll-view scroll-x class="chips"><view class="chip-row">
      <button class="chip" :class="{ selected: !tag }" @click="selectTag('')">全部</button>
      <button v-for="item in tags" :key="item.id" class="chip" :class="{ selected: tag === item.name }" @click="selectTag(item.name)">{{ item.name }}</button>
      <button class="chip manage" @click="showTags = true">⚙ 标签</button>
    </view></scroll-view>
    <view v-if="tagError" class="error" @click="loadTags">标签加载失败，点击重试</view>
    <view v-if="error" class="error" @click="retry">加载失败，点击重试</view>
    <view class="content">
      <view class="counter"><text>{{ tag || activeQuery ? '筛选出' : '共' }} {{ total }} 家合作商</text><text>按下次跟进计划排序</text></view>
      <template v-if="loading && !loaded && items.length === 0">
        <view v-for="n in 3" :key="n" class="skeleton"><view></view><view></view><view></view></view>
      </template>
      <template v-else>
        <view v-for="item in items" :key="item.id" class="card" @click="openPartner(item.id)">
          <view class="avatar">{{ item.name.slice(0, 1) }}</view>
          <view class="main">
            <view class="first"><text class="name">{{ item.name }}</text><text class="level" :class="levelClass(item.level)">{{ item.level }}</text></view>
            <view class="details"><text v-for="label in item.tags" :key="label" class="tag">{{ label }}</text><text>{{ item.industry || '—' }} · {{ item.city || '—' }} · {{ item.owner?.name || '—' }}</text></view>
            <view class="follow"><text class="dot" :class="item.status === '合作中' ? 'on' : item.status === '暂停' ? 'pause' : ''"></text>{{ item.status }} · {{ item.lastFollowAt ? '最近跟进 ' + formatList(item.lastFollowAt) : '暂无跟进' }}</view>
            <view v-if="item.plan" class="plan" :class="planClass(item.plan.date)"><text class="plan-date">{{ formatList(item.plan.date) }}</text><text class="plan-note">{{ relativeDay(item.plan.date) }}{{ item.plan.note ? ' · ' + item.plan.note : '' }}</text></view>
            <view v-else class="plan none"><text class="plan-date">待补计划</text><text>还没有计划</text></view>
          </view>
        </view>
        <view v-if="loaded && !loading && !error && !items.length" class="empty">
          <text>{{ tag || activeQuery ? '没有找到相关合作商' : '还没有合作商' }}</text>
          <button @click="tag || activeQuery ? clearFilters() : openNew()">{{ tag || activeQuery ? '清除筛选' : '新建合作商' }}</button>
        </view>
        <view v-if="loading && items.length" class="foot">加载中…</view>
        <view v-else-if="items.length && page * 20 < total && !error" class="foot" @click="loadMore">上拉加载更多</view>
      </template>
      <view v-if="items.length" class="foot">每家必须有「下次跟进计划」，列表按计划时间由近到远（含逾期置前）</view>
    </view>
    <TagSheet v-model="showTags" @changed="tagsChanged" />
  </view>
</template>

<script setup lang="ts">
import { ref, onUnmounted } from 'vue'
import { onLoad, onPullDownRefresh, onReachBottom } from '@dcloudio/uni-app'
import { listPartners, type Partner } from '../../api/partner'
import { listTags, type Tag } from '../../api/tag'
import TagSheet from '../../components/TagSheet.vue'
import { formatDetail, formatList, relativeDay } from '../../utils/date'

const items = ref<Partner[]>([])
const tags = ref<Tag[]>([])
const tag = ref('')
const query = ref('')
const activeQuery = ref('')
const searching = ref(false)
const showTags = ref(false)
const total = ref(0)
const page = ref(0)
const loading = ref(false)
const loaded = ref(false)
const error = ref(false)
const tagError = ref(false)
let generation = 0
let failedReset = true
let searchPending = false
let timer: ReturnType<typeof setTimeout> | undefined

async function loadTags() {
  try { tags.value = await listTags(); tagError.value = false } catch { tagError.value = true }
}
async function fetchPage(next: number, reset = false, preserve = false) {
  const current = ++generation
  loading.value = true
  error.value = false
  if (reset && !preserve) { loaded.value = false; items.value = []; total.value = 0; page.value = 0 }
  try {
    const result = await listPartners({ page: next, size: 20, ...(tag.value ? { tag: tag.value } : {}), ...(activeQuery.value.trim() ? { q: activeQuery.value.trim() } : {}) })
    if (current !== generation) return
    items.value = reset ? result.list : [...items.value, ...result.list]
    total.value = result.total
    page.value = result.page
    loaded.value = true
  } catch {
    if (current === generation) { error.value = true; failedReset = reset }
  } finally {
    if (current === generation) loading.value = false
  }
}
function refresh(preserve = false) { return fetchPage(1, true, preserve) }
function selectTag(value: string) { if (tag.value !== value) { clearTimeout(timer); searchPending = false; activeQuery.value = query.value; tag.value = value; refresh() } }
function onInput(event: Event) {
  query.value = (event as unknown as { detail: { value: string } }).detail.value
  searchPending = true
  clearTimeout(timer)
  // Invalidate immediately, not after the debounce, so older responses cannot win.
  generation++
  loading.value = false
  timer = setTimeout(() => { searchPending = false; activeQuery.value = query.value; refresh() }, 300)
}
function cancelSearch() { clearTimeout(timer); searchPending = false; searching.value = false; query.value = ''; activeQuery.value = ''; refresh() }
function clearFilters() { tag.value = ''; cancelSearch() }
function loadMore() { if (!searchPending && !loading.value && !error.value && loaded.value && page.value * 20 < total.value) fetchPage(page.value + 1) }
function retry() { if (searchPending) return; if (failedReset) refresh(items.value.length > 0); else fetchPage(page.value + 1) }
function openNew() { uni.navigateTo({ url: '/pages/company/companyNew' }) }
function openPartner(id: number) { uni.navigateTo({ url: `/pages/company/company?id=${id}` }) }
function levelClass(level: string) { return level === '战略' ? 'strategic' : level === '重点' ? 'important' : 'ordinary' }
function planClass(date: string) { const today = formatDetail(new Date()); return date < today ? 'overdue' : date === today ? 'today' : 'future' }
async function tagsChanged() { await loadTags(); if (tag.value && !tags.value.some(item => item.name === tag.value)) tag.value = ''; clearTimeout(timer); searchPending = false; activeQuery.value = query.value; refresh() }
onLoad(() => { loadTags(); refresh() })
onPullDownRefresh(async () => { const changed = activeQuery.value !== query.value; clearTimeout(timer); searchPending = false; activeQuery.value = query.value; try { await Promise.all([loadTags(), refresh(!changed)]) } finally { uni.stopPullDownRefresh() } })
onReachBottom(loadMore)
onUnmounted(() => { clearTimeout(timer); generation++ })
</script>

<style scoped>
.partners { min-height: 100vh; background: #f2f4f8; }
.nav { padding: calc(var(--status-bar-height, 0px) + 8px) 14px 8px; min-height: 44px; background: #fff; display: flex; align-items: center; justify-content: space-between; }
.nav-title { font-size: 19px; font-weight: 700; color: #161a22; }
.nav-actions { display: flex; gap: 8px; }
button { margin: 0; padding: 0; border: 0; background: transparent; line-height: normal; }
button::after { border: 0; }
.icon { width: 36px; height: 36px; color: #1f3864; font-size: 29px; }
.plus { font-size: 26px; }
.search-box { flex: 1; display: flex; align-items: center; gap: 8px; background: #eef1f6; border-radius: 10px; padding: 7px 11px; color: #98a2b3; }
.search-box input { flex: 1; font-size: 14px; color: #161a22; }
.cancel { padding-left: 14px; color: #2c4c86; font-size: 14px; }
.chips { white-space: nowrap; background: #fff; }
.chip-row { display: flex; gap: 8px; padding: 8px 14px 12px; }
.chip { flex: none; padding: 7px 13px; border-radius: 999px; background: #f2f4f8; color: #667085; font-size: 12px; }
.chip.selected { background: #1f3864; color: white; }
.chip.manage { background: #edf1f9; color: #2c4c86; }
.content { padding: 12px 14px 20px; }
.counter { display: flex; justify-content: space-between; color: #9aa3af; font-size: 11px; margin: 0 2px 9px; }
.card { display: flex; gap: 11px; padding: 14px; margin-bottom: 10px; border-radius: 15px; background: white; box-shadow: 0 1px 2px rgba(16,24,40,.05), 0 8px 24px rgba(16,24,40,.06); }
.avatar { flex: none; width: 38px; height: 38px; border-radius: 12px; display: flex; align-items: center; justify-content: center; background: #edf1f9; color: #2c4c86; font-weight: 700; }
.main { flex: 1; min-width: 0; }
.first { display: flex; align-items: center; gap: 8px; }
.name { flex: 1; min-width: 0; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; font-weight: 700; font-size: 15px; }
.level { flex: none; padding: 3px 8px; border-radius: 999px; font-size: 10px; }
.strategic { color: #2c4c86; background: #edf3ff; }.important { color: #9a6400; background: #fff3dc; }.ordinary { color: #6b7280; background: #f2f4f8; }
.details { margin-top: 6px; color: #6b7280; font-size: 12px; line-height: 1.7; }
.tag { display: inline-block; margin-right: 5px; padding: 0 5px; border-radius: 6px; background: #edf1f9; color: #3a5078; font-size: 10px; }
.follow { margin-top: 5px; color: #6b7280; font-size: 12px; }
.dot { display: inline-block; width: 6px; height: 6px; margin-right: 5px; border-radius: 50%; background: #9aa3af; vertical-align: middle; }.dot.on { background: #2e7d4f; }.dot.pause { background: #d49a20; }
.plan { display: flex; gap: 8px; margin-top: 9px; padding: 7px 9px; border-radius: 8px; font-size: 11px; }.plan-date { flex: none; font-weight: 700; }.plan-note { overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }.overdue { background: #fce9e7; color: #c0392b; }.today { background: #fff3dc; color: #9a6400; }.future { background: #edf3ff; color: #2c4c86; }.none { background: #f2f4f8; color: #9aa3af; }
.error { padding: 10px; text-align: center; background: #fce9e7; color: #c0392b; font-size: 12px; }
.empty { padding: 60px 0; text-align: center; color: #9aa3af; font-size: 14px; }.empty button { margin: 16px auto; padding: 9px 18px; border-radius: 10px; color: white; background: #1f3864; font-size: 13px; }
.foot { text-align: center; color: #9aa3af; font-size: 11px; line-height: 1.6; padding: 10px 0; }
.skeleton { padding: 18px; margin-bottom: 10px; background: white; border-radius: 15px; }.skeleton view { height: 12px; width: 75%; margin: 8px 0; border-radius: 6px; background: #eef1f6; }.skeleton view:first-child { width: 55%; }.skeleton view:last-child { width: 90%; }
</style>
