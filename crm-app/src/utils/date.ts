import dayjs from 'dayjs'
import utc from 'dayjs/plugin/utc'
import type { Dayjs } from 'dayjs'

dayjs.extend(utc)

export type DateInput = string | number | Date | Dayjs

const CST_OFFSET_MINUTES = 8 * 60
const DATE_ONLY = /^\d{4}-\d{2}-\d{2}$/
const DATE_TIME_WITHOUT_ZONE = /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}(:\d{2}(\.\d+)?)?$/

/**
 * 统一按 +08:00 解释日期。
 * - 纯日期按当天 00:00 +08:00；
 * - 不带时区的日期时间按 +08:00；
 * - 带时区的时间先转到 +08:00。
 */
function toCst(input: DateInput): Dayjs {
  if (typeof input === 'string' && DATE_ONLY.test(input)) {
    return dayjs(`${input}T00:00:00+08:00`).utcOffset(CST_OFFSET_MINUTES)
  }

  if (typeof input === 'string' && DATE_TIME_WITHOUT_ZONE.test(input)) {
    return dayjs(`${input.replace(' ', 'T')}+08:00`).utcOffset(CST_OFFSET_MINUTES)
  }

  return dayjs(input).utcOffset(CST_OFFSET_MINUTES)
}

function startOfCstDay(input: DateInput): Dayjs {
  return toCst(input).startOf('day')
}

/** 列表 / 卡片：MM-DD */
export function formatList(date: DateInput): string {
  return toCst(date).format('MM-DD')
}

/** 详情 / 接口：YYYY-MM-DD */
export function formatDetail(date: DateInput): string {
  return toCst(date).format('YYYY-MM-DD')
}

/** 相对日期：今天 / 明天 / N 天后 / 逾期 N 天 */
export function relativeDay(date: DateInput): string {
  const today = dayjs().utcOffset(CST_OFFSET_MINUTES).startOf('day')
  const target = startOfCstDay(date)
  const diff = target.diff(today, 'day')

  if (diff === 0) {
    return '今天'
  }
  if (diff === 1) {
    return '明天'
  }
  if (diff > 1) {
    return `${diff} 天后`
  }
  return `逾期 ${Math.abs(diff)} 天`
}
