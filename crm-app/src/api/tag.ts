import request from '../utils/request'

/** 标签字典项（GET /tags）。 */
export interface Tag {
  id: number
  name: string
  sort: number
  enabled: boolean
  /** 引用该标签的未删除合作商数 */
  orgCount: number
}

export interface CreatedTag {
  id: number
  name: string
  sort: number
}

export interface RenamedTag {
  id: number
  name: string
}

/** 请求层已校验 code===0，返回的是整个 {code,message,data} 包体，这里统一取出 data。 */
const dataOf = <T>(body: unknown): T => (body as { data: T }).data

export const listTags = async (): Promise<Tag[]> => {
  const data = dataOf<Tag[] | null | undefined>(await request.get('/tags'))
  return Array.isArray(data) ? data : []
}

export const createTag = async (name: string): Promise<CreatedTag> =>
  dataOf<CreatedTag>(await request.post('/tags', { name }))

export const updateTag = async (id: number, name: string): Promise<RenamedTag> =>
  dataOf<RenamedTag>(await request.put(`/tags/${id}`, { name }))

export const deleteTag = async (id: number): Promise<void> => {
  await request.delete(`/tags/${id}`)
}
