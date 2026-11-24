import request from '../utils/request'

/** 来源字典项（读取自 GET /meta/dicts 的 leadSources）。 */
export interface LeadSource {
  id: number
  name: string
  sort?: number
  /** 被 lead 引用的条数；由 /meta/dicts 提供时展示 */
  refCount?: number
}

export interface LeadSourceCreated {
  id: number
  name: string
  sort: number
}

export interface LeadSourceRenamed {
  id: number
  name: string
}

interface DictsResponse {
  data?: {
    leadSources?: LeadSource[]
  }
}

/**
 * 来源字典的读取走 GET /meta/dicts（BE-API-041，由另一任务实现）。
 */
export const listSources = async (): Promise<LeadSource[]> => {
  const body = (await request.get('/meta/dicts')) as DictsResponse | undefined
  const leadSources = body?.data?.leadSources
  return Array.isArray(leadSources) ? leadSources : []
}

/** 请求层已校验 code===0，返回的是整个 {code,message,data} 包体，这里统一取出 data。 */
const dataOf = <T>(body: unknown): T => (body as { data: T }).data

export const createSource = async (name: string): Promise<LeadSourceCreated> =>
  dataOf<LeadSourceCreated>(await request.post('/lead-sources', { name }))

export const updateSource = async (id: number, name: string): Promise<LeadSourceRenamed> =>
  dataOf<LeadSourceRenamed>(await request.put(`/lead-sources/${id}`, { name }))

export const deleteSource = async (id: number): Promise<void> => {
  await request.delete(`/lead-sources/${id}`)
}
