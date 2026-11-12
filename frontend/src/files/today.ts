/** Today in Eastern time, as YYYY-MM-DD: the server's business clock (BankingCalendarConfig), not the browser's zone. */
export const todayET = (now = new Date()) => now.toLocaleDateString('en-CA', { timeZone: 'America/New_York' })
