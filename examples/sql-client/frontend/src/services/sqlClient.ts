import type { BridgeError } from '@sugr/runtime'
import { SqlService } from '../generated/SqlService.generated'

export type Row = Record<string, string | null>

/** Thin re-export - components call this instead of the generated stub directly, so a
 * future non-generated concern (retries, logging, ...) has one place to live. */
export const { connect, query, listTables, pickDatabaseFile, copyToClipboard } = SqlService

export function isDialogCancelled(error: unknown): boolean {
  return (error as BridgeError)?.code === 'DIALOG_CANCELLED'
}

export function errorMessage(error: unknown): string {
  const err = error as BridgeError
  return err?.message ?? String(error)
}

/** Renders rows as TSV so pasting into a spreadsheet keeps columns aligned. */
export function rowsToClipboardText(rows: Row[]): string {
  if (rows.length === 0) {
    return ''
  }
  const columns = Object.keys(rows[0])
  const lines = [columns.join('\t')]
  for (const row of rows) {
    lines.push(columns.map((col) => row[col] ?? '').join('\t'))
  }
  return lines.join('\n')
}
