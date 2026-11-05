import { formatDate, frequencyLabel, masked, money } from './rules'
import type { Receipt } from './types'

export function statusLabel(r: Pick<Receipt, 'ofacStatus' | 'processStatus'>): string {
  if (r.ofacStatus === 'FLAGGED') return 'Under review'
  if (r.processStatus === 'PENDING_BATCH') return 'Scheduled'
  if (r.processStatus === 'PROCESSED') return 'Processed'
  return r.processStatus === 'REJECTED' ? 'Rejected' : r.processStatus
}

/** The text of the downloadable receipt (UC-ACH-ORG-01 step 7). Account numbers are masked to the last 4. */
export function receiptText(r: Receipt): string {
  const lines = [
    'Demo Credit Union',
    'External ACH Transfer Receipt',
    '',
    `Reference number:   ${r.transferSerNo}`,
    `Submitted:          ${r.submitted.replace('T', ' ').slice(0, 19)}`,
    `Status:             ${statusLabel(r)}`,
    '',
    `From:               ${r.fromAcctName} ${masked(r.fromAcctLast4)}`,
    `To:                 ${r.recvName}`,
    `Bank:               ${r.bankName} (routing ${r.routingNo})`,
    `Account:            ${r.acctType === 'SAVINGS' ? 'Savings' : 'Checking'} ${masked(r.acctLast4)}`,
    '',
    `Amount:             ${money(r.amount)}`,
    `Fee:                ${money(r.fee)}`,
    `Total:              ${money(r.total)}`,
    `Transfer date:      ${formatDate(r.effectiveDt)}`,
    `Estimated arrival:  ${formatDate(r.estimatedArrival)}`,
    `Frequency:          ${frequencyLabel(r.frequency)}${r.transferCount > 1 ? ` (${r.transferCount} transfers)` : ''}`,
  ]
  if (r.memo) lines.push(`Memo:               ${r.memo}`)
  lines.push('', 'Keep this receipt for your records. Questions? Contact Member Services.')
  return lines.join('\n') + '\n'
}
