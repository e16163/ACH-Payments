export type Account = { acctNo: string; acctName: string; acctType: string; availBal: string }

export type Preview = {
  bankName: string
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
  bankName: string
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
