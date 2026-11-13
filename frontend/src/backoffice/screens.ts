// The back-office screens (docs/USE-CASES.md UC-ACH-BO), each behind one of Ba's permissions (docs/LDAP-PLAN.md). Read-only, and guessed from the table design until their use cases arrive.
export type Section = { heading: string; path: string; columns: string[] }
export type Screen = { route: string; title: string; permission: string; sections: Section[] }

const ORIG = '/v1/ach/back-office/origination'
const RECV = '/v1/ach/back-office/receiving'
const RETURN_COLUMNS = ['returnID', 'returnDt', 'returnCode', 'amount', 'companyName', 'acctName', 'returnStatus', 'returnTraceNo']
const ACTION_COLUMNS = ['processID', 'actionName', 'actionTm', 'lastModBy']
const FILE_ACTION_COLUMNS = ['fileID', ...ACTION_COLUMNS, 'fileHeaderDt', 'fedStatus']
const NOC_COLUMNS = ['nocID', 'nocCode', 'correctedData', 'origTraceNo', 'nocTraceNo', 'createTm']
const ACCT_SUB_COLUMNS = ['nocAcctNo', 'nocRoutingNo', 'subsAcctNo', 'subsRoutingNo', 'acctOwner', 'acctStatus']
const TRAN_CODE_SUB_COLUMNS = ['nocID', 'nocAcctNo', 'nocRtn', 'nocTranCode', 'subsTranCode', 'companyID']

export const SCREENS: Screen[] = [
  {
    route: '#/ach-origination-return', title: 'ACH Origination Return', permission: 'ACH_ORIG_Return_UI',
    sections: [
      { heading: 'Returns on entries we sent', path: `${ORIG}/returns`, columns: RETURN_COLUMNS },
      { heading: 'Return actions', path: `${ORIG}/return-actions`, columns: ['returnID', ...ACTION_COLUMNS] },
    ],
  },
  {
    route: '#/ach-origination-reversal', title: 'ACH Origination Reversal', permission: 'ACH_ORIG_Reversal_UI',
    sections: [{ heading: 'Reversal actions', path: `${ORIG}/reversals`, columns: FILE_ACTION_COLUMNS }],
  },
  {
    route: '#/ach-origination-resubmission', title: 'ACH Origination Resubmission', permission: 'ACH_ORIG_Resubmision_UI',
    sections: [{ heading: 'Resubmission actions', path: `${ORIG}/resubmissions`, columns: FILE_ACTION_COLUMNS }],
  },
  {
    route: '#/ach-origination-noc', title: 'ACH Origination NOC', permission: 'ACH_ORIG_PROCESS',
    sections: [
      { heading: 'NOCs we received', path: `${ORIG}/nocs`, columns: [...NOC_COLUMNS, 'notifyByDt', 'applyByDt'] },
      { heading: 'NOC actions', path: `${ORIG}/noc-actions`, columns: ['nocID', ...ACTION_COLUMNS] },
      { heading: 'Account substitutions', path: `${ORIG}/account-subs`, columns: ACCT_SUB_COLUMNS },
      { heading: 'Transaction code substitutions', path: `${ORIG}/trancode-subs`, columns: TRAN_CODE_SUB_COLUMNS },
    ],
  },
  {
    route: '#/ach-receiving-return', title: 'ACH Receiving Return', permission: 'ACH_RECV_Return_UI',
    sections: [
      { heading: 'Returns we sent', path: `${RECV}/returns`, columns: [...RETURN_COLUMNS, 'dueDt'] },
      { heading: 'Return requests', path: `${RECV}/return-requests`, columns: ['requestID', 'tranID', 'requestingRtn', 'requestReason', 'receivedDt', 'dueDt', 'decision'] },
    ],
  },
  {
    route: '#/ach-receiving-reversal', title: 'ACH Receiving Reversal', permission: 'ACH_RECV_Reversal_UI',
    sections: [{ heading: 'Reversals received', path: `${RECV}/reversals`, columns: ['tranID', 'effectiveDt', 'amount', 'companyName', 'recvName', 'traceNo', 'fedStatus'] }],
  },
  {
    route: '#/ach-receiving-noc', title: 'ACH Receiving NOC', permission: 'ACH_RECV_PROCESS',
    sections: [
      { heading: 'NOCs we sent', path: `${RECV}/nocs`, columns: [...NOC_COLUMNS, 'nocStatus', 'dueDt'] },
      { heading: 'Account substitutions', path: `${RECV}/account-subs`, columns: ACCT_SUB_COLUMNS },
      { heading: 'Transaction code substitutions', path: `${RECV}/trancode-subs`, columns: TRAN_CODE_SUB_COLUMNS },
    ],
  },
]
