export type Account = { acctNo: string; acctName: string; acctType: string; availBal: string }

/** One of the member's registered external bank accounts (Ba's GET /v1/holders/{holderId}/external-accounts). */
export type ExternalAccount = { externalAccountId: string; bankName: string | null; maskedAccountNumber: string; accountType: string; status: string }

export type Preview = {
  bankName: string | null
  amount: string
  fee: string
  total: string
  effectiveDt: string
  estimatedArrival: string
  underReview: boolean
}

export type Receipt = {
  transferSerNo: string
  processStatus: string
  ofacStatus: string
  memoStatus: string
  recvName: string
  bankName: string | null
  routingNo: string
  acctLast4: string
  acctType: string
  fromAcctName: string
  fromAcctLast4: string
  amount: string
  fee: string
  total: string
  effectiveDt: string
  estimatedArrival: string
  frequency: string
  transferCount: number
  memo: string | null
  submitted: string
}
