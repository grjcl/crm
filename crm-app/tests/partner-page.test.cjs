// Run with: node --test tests/partner-page.test.cjs
// Exercises the real Vue SFC with an in-memory renderer and mocked API calls.
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const vm = require('node:vm')
const { parse, compileScript } = require('@vue/compiler-sfc')
const ts = require('typescript')
const Vue = require('vue')

function loadPage(mocks) {
  const filename = path.resolve(__dirname, '../src/pages/partners/partners.vue')
  const { descriptor, errors } = parse(fs.readFileSync(filename, 'utf8'), { filename })
  assert.deepEqual(errors, [])
  const script = compileScript(descriptor, { id: 'partner-test', inlineTemplate: true, templateOptions: { compilerOptions: { isCustomElement: tag => tag === 'scroll-view' } } })
  const js = ts.transpileModule(script.content, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2020, esModuleInterop: true } }).outputText
  const module = { exports: {} }
  const fakeRequire = (name) => {
    if (name === 'vue') return Vue
    if (name === '@dcloudio/uni-app') return mocks.lifecycle
    if (name.endsWith('/api/partner')) return mocks.partner
    if (name.endsWith('/api/tag')) return mocks.tag
    if (name.endsWith('/utils/date')) return mocks.date
    if (name.endsWith('/components/TagSheet.vue')) return { render: () => null }
    throw new Error(`Unmocked import: ${name}`)
  }
  vm.runInNewContext(js, { module, exports: module.exports, require: fakeRequire, uni: mocks.uni, setTimeout, clearTimeout, console }, { filename })
  return module.exports.default
}

const renderer = Vue.createRenderer({
  createElement: type => ({ type, children: [], props: {}, parent: null }),
  createText: text => ({ type: '#text', text, parent: null }),
  createComment: text => ({ type: '#comment', text, parent: null }),
  setText: (node, text) => { node.text = text },
  setElementText: (node, text) => { node.children = []; node.text = text },
  parentNode: node => node.parent,
  nextSibling: node => node.parent?.children[node.parent.children.indexOf(node) + 1] || null,
  insert: (node, parent, anchor) => {
    if (node.parent) {
      const old = node.parent.children.indexOf(node)
      if (old >= 0) node.parent.children.splice(old, 1)
    }
    node.parent = parent
    const index = anchor ? parent.children.indexOf(anchor) : -1
    parent.children.splice(index < 0 ? parent.children.length : index, 0, node)
  },
  remove: node => {
    if (!node.parent) return
    const index = node.parent.children.indexOf(node)
    if (index >= 0) node.parent.children.splice(index, 1)
    node.parent = null
  },
  patchProp: (node, key, _old, value) => { node.props[key] = value },
})
function text(node) { return [node.text || '', ...(node.children || []).map(text)].join(' ') }
function descendants(node) { return [node, ...(node.children || []).flatMap(descendants)] }
function deferred() {
  let resolve, reject
  const promise = new Promise((res, rej) => { resolve = res; reject = rej })
  return { promise, resolve, reject }
}
async function flush() { await Promise.resolve(); await Promise.resolve(); await Vue.nextTick() }

function mountPage() {
  const hooks = {}
  const requests = []
  const lifecycle = new Proxy({}, { get: (_, name) => callback => { hooks[name] = callback } })
  const mocks = {
    lifecycle,
    partner: { listPartners(params) { const call = deferred(); requests.push({ params, ...call }); return call.promise } },
    tag: { listTags: async () => [{ id: 1, name: '酒店', enabled: true, sort: 1, orgCount: 2 }] },
    date: { formatList: value => value.slice(5), formatDetail: () => '2026-06-15', relativeDay: () => '今天' },
    uni: { navigateTo() {}, stopPullDownRefresh() {}, showToast() {} },
  }
  const root = { type: 'root', children: [] }
  const app = renderer.createApp(loadPage(mocks))
  app.component('scroll-view', { render() { return Vue.h('scroll-view', this.$slots.default?.()) } })
  app.mount(root)
  hooks.onLoad()
  return { app, root, hooks, requests }
}

function page(list, total = list.length, pageNumber = 1) { return { list, total, page: pageNumber, size: 20 } }
function partner(id, date = null) { return { id, name: `企业${id}`, level: '重点', tags: ['酒店'], industry: '旅游', city: '上海', owner: { name: '销售' }, status: '合作中', lastFollowAt: '2026-06-01', plan: date ? { date, note: '跟进' } : null } }
function byText(root, value) { return descendants(root).find(n => n.type === 'button' && text(n).trim() === value) }
function byClass(root, cls) { return descendants(root).filter(n => (n.props?.class || '').split(' ').includes(cls)) }
function click(node) { assert.ok(node, 'element not found'); node.props.onClick() }

test('renders list, plan states, empty results and retry', async () => {
  const h = mountPage()
  await flush()
  assert.equal(byClass(h.root, 'skeleton').length, 3)
  h.requests[0].resolve(page([partner(1, '2026-06-14'), partner(2, '2026-06-15'), partner(3, '2026-06-16'), partner(4)]))
  await flush()
  assert.match(text(h.root), /企业1/)
  assert.match(text(h.root), /旅游 · 上海 · 销售/)
  assert.equal(byClass(h.root, 'card').length, 4)
  for (const cls of ['overdue', 'today', 'future', 'none']) assert.equal(byClass(h.root, cls).length, 1)
  click(byText(h.root, '酒店'))
  h.requests[1].resolve(page([]))
  await flush()
  assert.match(text(h.root), /没有找到相关合作商/)
  click(byText(h.root, '全部'))
  h.requests[2].reject(new Error('offline'))
  await flush()
  assert.match(text(h.root), /加载失败，点击重试/)
  click(byClass(h.root, 'error')[0])
  h.requests[3].resolve(page([partner(5)]))
  await flush()
  assert.match(text(h.root), /企业5/)
  h.app.unmount()
})

test('changing tag during debounce uses pending query without a second request', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const h = mountPage()
  h.requests[0].resolve(page([partner(1)]))
  await flush()
  click(descendants(h.root).find(n => n.props?.['aria-label'] === '搜索'))
  await flush()
  descendants(h.root).find(n => n.type === 'input').props.onInput({ detail: { value: '酒店集团' } })
  t.mock.timers.tick(100)
  click(byText(h.root, '酒店'))
  assert.equal(h.requests.length, 2)
  assert.equal(h.requests[1].params.q, '酒店集团')
  assert.equal(h.requests[1].params.tag, '酒店')
  t.mock.timers.tick(500)
  assert.equal(h.requests.length, 2)
  h.requests[1].resolve(page([partner(2)]))
  await flush()
  assert.match(text(h.root), /企业2/)
  h.app.unmount()
})

test('suppresses pagination during search debounce and fetches latest query', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const h = mountPage()
  h.requests[0].resolve(page([partner(1)], 21))
  await flush()
  click(descendants(h.root).find(n => n.props?.['aria-label'] === '搜索'))
  await flush()
  const input = descendants(h.root).find(n => n.type === 'input')
  input.props.onInput({ detail: { value: '旧词' } })
  t.mock.timers.tick(150)
  input.props.onInput({ detail: { value: '最新词' } })
  h.hooks.onReachBottom()
  assert.equal(h.requests.length, 1)
  t.mock.timers.tick(299)
  assert.equal(h.requests.length, 1)
  t.mock.timers.tick(1)
  assert.equal(h.requests.length, 2)
  assert.equal(h.requests[1].params.page, 1)
  assert.equal(h.requests[1].params.q, '最新词')
  h.requests[1].resolve(page([partner(2)]))
  await flush()
  assert.match(text(h.root), /企业2/)
  h.app.unmount()
})

test('failed pull refresh retains cards and retry reloads page one', async () => {
  const h = mountPage()
  h.requests[0].resolve(page([partner(1)], 21))
  await flush()
  h.hooks.onReachBottom()
  h.requests[1].resolve(page([partner(2)], 21, 2))
  await flush()
  const refresh = h.hooks.onPullDownRefresh()
  assert.equal(h.requests[2].params.page, 1)
  h.requests[2].reject(new Error('offline'))
  await refresh
  await flush()
  assert.match(text(h.root), /企业1/)
  assert.match(text(h.root), /企业2/)
  assert.match(text(h.root), /加载失败，点击重试/)
  click(byClass(h.root, 'error')[0])
  assert.equal(h.requests[3].params.page, 1)
  h.requests[3].resolve(page([partner(3)]))
  await flush()
  assert.match(text(h.root), /企业3/)
  assert.doesNotMatch(text(h.root), /企业1/)
  h.app.unmount()
})

test('combines tag and debounced search, ignores stale responses, cancels and paginates', async t => {
  t.mock.timers.enable({ apis: ['setTimeout'] })
  const h = mountPage()
  await flush()
  h.requests[0].resolve(page([partner(1)], 21))
  await flush()
  click(byText(h.root, '酒店'))
  await flush()
  const stale = h.requests[1]
  click(descendants(h.root).find(n => n.props?.['aria-label'] === '搜索'))
  await flush()
  const input = descendants(h.root).find(n => n.type === 'input')
  input.props.onInput({ detail: { value: '企业' } })
  t.mock.timers.tick(299)
  assert.equal(h.requests.length, 2)
  t.mock.timers.tick(1)
  assert.equal(h.requests.length, 3)
  assert.equal(h.requests[2].params.tag, '酒店')
  assert.equal(h.requests[2].params.q, '企业')
  h.requests[2].resolve(page([partner(2)], 21))
  await flush()
  stale.resolve(page([partner(99)]))
  await flush()
  assert.match(text(h.root), /企业2/)
  assert.doesNotMatch(text(h.root), /企业99/)
  h.hooks.onReachBottom()
  assert.equal(h.requests[3].params.page, 2)
  h.requests[3].resolve(page([partner(3)], 21, 2))
  await flush()
  assert.match(text(h.root), /企业2/)
  assert.match(text(h.root), /企业3/)
  click(byText(h.root, '取消'))
  assert.equal(h.requests[4].params.tag, '酒店')
  assert.equal(h.requests[4].params.q, undefined)
  h.requests[4].resolve(page([partner(4)]))
  await flush()
  assert.match(text(h.root), /企业4/)
  h.app.unmount()
})
