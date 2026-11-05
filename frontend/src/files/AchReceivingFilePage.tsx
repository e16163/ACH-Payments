import { useEffect, useState } from 'react'
import { api } from '../api'
import { formatDate } from '../member/rules'

type FileRow = {
  fileID: string; fileName: string; fileHeaderDt: string; fileHeaderTm: string; fedStatus: string
  entryCount: number; totalCreditAmt: number; totalDebitAmt: number
}

const usd = (n: number) => n.toLocaleString('en-US', { style: 'currency', currency: 'USD' })

/** The files the Fed sends us each morning. Read-only until Ba's ACH Receiving Engine use case. */
export default function AchReceivingFilePage() {
  const [files, setFiles] = useState<FileRow[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    api<FileRow[]>('/receiving/files').then(setFiles).catch((e) => setError(String(e.message ?? e)))
  }, [])

  if (!files) return error ? <p className="page-error">{error}</p> : null

  return (
    <>
      <h2>ACH Receiving File</h2>
      <p className="hint">
        The Fed sends this file at 6:00 AM (Eastern) each morning. Automatic receipt and processing come with the ACH
        Receiving Engine; this page lists what's in ACHRecvFile today.
      </p>

      <h3 className="section">Files</h3>
      {files.length === 0 ? <p className="empty">No files yet.</p> : (
        <table aria-label="Files">
          <thead>
            <tr><th>File</th><th>Date</th><th>Header time</th><th>Status</th><th>Entries</th><th>Credits</th><th>Debits</th></tr>
          </thead>
          <tbody>
            {files.map((f) => (
              <tr key={f.fileID}>
                <td className="code">{f.fileName}</td>
                <td>{formatDate(f.fileHeaderDt)}</td>
                <td>{f.fileHeaderTm.slice(0, 5)}</td>
                <td>{f.fedStatus}</td>
                <td>{f.entryCount}</td>
                <td>{usd(f.totalCreditAmt)}</td>
                <td>{usd(f.totalDebitAmt)}</td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
    </>
  )
}
