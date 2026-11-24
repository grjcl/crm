import request from '../utils/request'

/** 用户角色（sys_user.role） */
export type UserRole = 'SALES' | 'ADMIN'

/**
 * GET /users 出参项：选人用最小字段集。
 * 只有脱敏手机号，没有 password / lastLoginAt / 明文手机号。
 */
export interface UserBrief {
  id: number
  name: string
  dept: string | null
  employeeNo: string | null
  role: UserRole
  phoneMasked: string
}

export interface UserListParams {
  /** 默认 true 只返回 ACTIVE；SALES 传 false 仍只返回 ACTIVE */
  enabled?: boolean
  /** 模糊匹配 name / employee_no，最长 40 */
  q?: string
}

interface UserListResponse {
  data?: UserBrief[]
}

/**
 * 全员列表（不分页，供协作人/交接/分配/负责人选人）。
 * 登录即可调用；选择停用用户只有 ADMIN 生效。
 */
export const listUsers = async (params: UserListParams = {}): Promise<UserBrief[]> => {
  const query: Record<string, string | boolean> = {}
  if (params.enabled !== undefined) {
    query.enabled = params.enabled
  }
  if (params.q !== undefined && params.q !== '') {
    query.q = params.q
  }

  const body = (await request.get('/users', { params: query })) as UserListResponse | undefined
  return Array.isArray(body?.data) ? body.data : []
}
