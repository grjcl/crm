import request from '../utils/request'

export interface Partner {
  id: number
  name: string
  shortName: string | null
  level: string
  status: string
  city: string | null
  type: string | null
  industry: string | null
  owner: { id: number; name: string } | null
  tags: string[]
  lastFollowAt: string | null
  plan: { date: string; note: string | null; overdueDays: number; daysFromToday: number } | null
}

export interface PartnerPage {
  list: Partner[]
  total: number
  page: number
  size: number
}

export async function listPartners(params: { page: number; size: number; tag?: string; q?: string }): Promise<PartnerPage> {
  const body = await request.get('/partners', { params }) as unknown as { data: PartnerPage }
  return body.data
}
