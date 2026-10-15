import { convertMinor } from '../lib/fxPreview'
import { type Scenario, ScenarioContext } from './runner'

const outcome = ScenarioContext.outcome

/** Every requirement of the assignment, as something you can run. Each scenario creates its own accounts. */
export const SCENARIOS: Scenario[] = [
  {
    id: 'atomic',
    title: 'Atomic transfer',
    proves: 'POST /transactions moves money from source to destination in one step, by the same amount on both sides.',
    async run(ctx) {
      const a = await ctx.funded('atomic A', 'SGD', 10_000)
      const b = await ctx.customer('atomic B', 'SGD')
      const reply = await ctx.transfer(a, b, 2_500)
      ctx.expect('transfer accepted', reply.status, 201)
      ctx.expect('confirmation: status POSTED', reply.json?.status, 'POSTED')
      ctx.expect('two postings: one debit, one credit', reply.json?.postings?.length, 2)
      ctx.expect('source decreased by 25.00', await ctx.balance(a), 7_500)
      ctx.expect('destination increased by 25.00', await ctx.balance(b), 2_500)
    },
  },
  {
    id: 'insufficient',
    title: 'Insufficient funds',
    proves: 'A transfer larger than the balance is refused and changes nothing.',
    async run(ctx) {
      const a = await ctx.funded('poor A', 'SGD', 1_000)
      const b = await ctx.customer('poor B', 'SGD')
      ctx.expect('10.01 from an account holding 10.00 is refused', outcome(await ctx.transfer(a, b, 1_001)),
        '422 INSUFFICIENT_FUNDS')
      ctx.expect('source unchanged', await ctx.balance(a), 1_000)
      ctx.expect('destination unchanged', await ctx.balance(b), 0)
    },
  },
  {
    id: 'bad-requests',
    title: 'Bad requests are refused',
    proves: 'Fractional or text amounts, a missing Idempotency-Key and a transfer to oneself never move money.',
    async run(ctx) {
      const a = await ctx.funded('strict A', 'SGD', 1_000)
      const b = await ctx.customer('strict B', 'SGD')
      const body = (amount: string) => `{"sourceAccountId":"${a}","destinationAccountId":"${b}","amount":${amount}}`
      ctx.expect('amount 10.5 -> 400', outcome(await ctx.post('/transactions', body('10.5'))), '400 VALIDATION_FAILED')
      ctx.expect('amount "100" -> 400', outcome(await ctx.post('/transactions', body('"100"'))), '400 VALIDATION_FAILED')
      ctx.expect('no Idempotency-Key -> 400', outcome(await ctx.transfer(a, b, 100, null)), '400 IDEMPOTENCY_KEY_MISSING')
      ctx.expect('same account -> 422', outcome(await ctx.transfer(a, a, 100)), '422 SAME_ACCOUNT')
      ctx.expect('balance unchanged', await ctx.balance(a), 1_000)
    },
  },
  {
    id: 'retry',
    title: 'Retry safety',
    proves: 'Sending the same request again returns the original transaction instead of moving money twice.',
    async run(ctx) {
      const a = await ctx.funded('retry A', 'SGD', 10_000)
      const b = await ctx.customer('retry B', 'SGD')
      const key = crypto.randomUUID()
      const first = await ctx.transfer(a, b, 2_500, key)
      const again = await ctx.transfer(a, b, 2_500, key)
      ctx.expect('first request accepted', first.status, 201)
      ctx.expect('same key + same body -> 201 again', again.status, 201)
      ctx.expect('replay is flagged (Idempotent-Replayed: true)', again.headers.get('Idempotent-Replayed'), 'true')
      ctx.expect('same transaction id', again.json?.id, first.json?.id)
      ctx.expect('byte-identical body', again.text, first.text)
      ctx.expect('same key + different body -> 422', outcome(await ctx.transfer(a, b, 9_999, key)),
        '422 IDEMPOTENCY_KEY_REUSED')
      ctx.expect('money moved once', await ctx.balance(b), 2_500)
    },
  },
  {
    id: 'race',
    title: 'Idempotency race',
    proves: '20 copies of one request sent at the same moment create exactly one transaction.',
    async run(ctx) {
      const a = await ctx.funded('race A', 'SGD', 10_000)
      const b = await ctx.customer('race B', 'SGD')
      const key = crypto.randomUUID()
      const replies = await Promise.all(Array.from({ length: 20 }, () => ctx.transfer(a, b, 100, key)))
      ctx.expect('all 20 answered 201', replies.filter((r) => r.status === 201).length, 20)
      ctx.expect('one transaction id among them', new Set(replies.map((r) => r.json?.id)).size, 1)
      ctx.expect('19 flagged as replays', replies.filter((r) => r.headers.get('Idempotent-Replayed') === 'true').length, 19)
      ctx.expect('money moved once', await ctx.balance(b), 100)
    },
  },
  {
    id: 'concurrency',
    title: 'Simultaneous transfers',
    proves: '20 different transfers into one account at once are all applied: no update is lost.',
    async run(ctx) {
      const a = await ctx.funded('burst A', 'SGD', 10_000)
      const b = await ctx.customer('burst B', 'SGD')
      const replies = await Promise.all(Array.from({ length: 20 }, () => ctx.transfer(a, b, 100)))
      ctx.expect('all 20 accepted', replies.filter((r) => r.status === 201).length, 20)
      ctx.expect('receiver got exactly 20 × 1.00', await ctx.balance(b), 2_000)
      ctx.expect('sender paid exactly 20 × 1.00', await ctx.balance(a), 8_000)
    },
  },
  {
    id: 'history',
    title: 'History in time order',
    proves: 'GET /accounts/{id}/transactions lists postings oldest first, each with the balance after it.',
    async run(ctx) {
      const a = await ctx.funded('history A', 'SGD', 10_000)
      const b = await ctx.funded('history B', 'SGD', 5_000)
      const out = await ctx.transfer(a, b, 2_500)
      const back = await ctx.transfer(b, a, 1_000)
      const items = (await ctx.get(`/accounts/${a}/transactions`)).json?.items ?? []
      ctx.expect('three entries', items.length, 3)
      ctx.expect('first is the deposit', items[0]?.type, 'DEPOSIT')
      ctx.expect('second is the outgoing transfer', items[1]?.transactionId, out.json?.id)
      ctx.expect('third is the incoming transfer', items[2]?.transactionId, back.json?.id)
      ctx.expect('running balance 100.00 -> 75.00 -> 85.00',
        items.map((i: { balanceAfter: { amount: number } }) => i.balanceAfter.amount).join(' -> '), '10000 -> 7500 -> 8500')
    },
  },
  {
    id: 'reversal',
    title: 'Reversal',
    proves: 'A transaction is corrected by posting its mirror image, once; a reversal cannot itself be reversed.',
    async run(ctx) {
      const a = await ctx.funded('reverse A', 'SGD', 10_000)
      const b = await ctx.customer('reverse B', 'SGD')
      const original = (await ctx.transfer(a, b, 2_500)).json?.id
      const reversal = await ctx.post(`/transactions/${original}/reversals`, { reason: 'scenario' })
      ctx.expect('reversal accepted', reversal.status, 201)
      ctx.expect('source restored', await ctx.balance(a), 10_000)
      ctx.expect('destination back to zero', await ctx.balance(b), 0)
      ctx.expect('original links to its reversal', (await ctx.get(`/transactions/${original}`)).json?.reversedById,
        reversal.json?.id)
      ctx.expect('second reversal -> 409', outcome(await ctx.post(`/transactions/${original}/reversals`, { reason: 'again' })),
        '409 ALREADY_REVERSED')
      ctx.expect('reversing the reversal -> 422',
        outcome(await ctx.post(`/transactions/${reversal.json?.id}/reversals`, { reason: 'undo' })), '422 CANNOT_REVERSE_REVERSAL')
    },
  },
  {
    id: 'fx',
    title: 'Cross-currency transfer',
    proves: 'SGD to USD goes through the house FX wallets at the stored rate, rounded down, and reverses exactly.',
    async run(ctx) {
      const sgd = await ctx.funded('fx sender', 'SGD', 10_000)
      const usd = await ctx.customer('fx receiver', 'USD')
      const rate = (await ctx.get('/fx/rates')).json?.find((r: { base: string; quote: string }) =>
        r.base === 'SGD' && r.quote === 'USD')?.rate
      const reply = await ctx.transfer(sgd, usd, 10_000)
      ctx.expect('FX transfer accepted', reply.json?.type, 'FX_TRANSFER')
      ctx.expect('four postings (two per currency)', reply.json?.postings?.length, 4)
      ctx.expect(`received SGD 100.00 × ${rate}, rounded down`, await ctx.balance(usd), convertMinor(10_000, rate, 2, 2))
      ctx.expect('rate recorded on the transaction', reply.json?.fx?.rate, rate)
      const reversal = await ctx.post(`/transactions/${reply.json?.id}/reversals`, { reason: 'scenario' })
      ctx.expect('reversed at the original rate', reversal.status, 201)
      ctx.expect('sender back to SGD 100.00', await ctx.balance(sgd), 10_000)
      ctx.expect('receiver back to zero', await ctx.balance(usd), 0)
    },
  },
  {
    id: 'external-ref',
    title: 'One outside deposit, booked once',
    proves: 'The same external reference (e.g. a Fireblocks transaction id) is refused a second time, even under a new key.',
    async run(ctx) {
      const a = await ctx.customer('external A', 'SGD')
      const ref = `fb-tx-${crypto.randomUUID().slice(0, 8)}`
      const first = await ctx.post('/deposits', { accountId: a, amount: 5_000, externalRef: ref })
      ctx.expect('deposit with an external reference', first.status, 201)
      ctx.expect('reference stored', first.json?.externalRef, ref)
      ctx.expect('same reference, new key -> 409',
        outcome(await ctx.post('/deposits', { accountId: a, amount: 5_000, externalRef: ref })), '409 EXTERNAL_REF_ALREADY_USED')
      ctx.expect('credited once', await ctx.balance(a), 5_000)
    },
  },
  {
    id: 'integrity',
    title: 'Total money adds up',
    proves: 'The integrity checks re-add everything: balances, money supply per currency and every hash-chain link.',
    async run(ctx) {
      const report = (await ctx.post('/integrity/checks')).json
      const head = (await ctx.get('/ledger/head')).json
      ctx.expect('integrity run is CLEAN', report?.status, 'CLEAN')
      ctx.expect('money in wallets = money deposited, every currency',
        report?.moneySupply?.every((line: { matches: boolean }) => line.matches), true)
      ctx.expect('hash chain valid', report?.chain?.valid, true)
      ctx.expect('chain ends at the ledger head', report?.chain?.headHash, head?.headHash)
    },
  },
  {
    id: 'month-end',
    title: 'Month-end reconciliation',
    proves: 'Every account: opening + credits − debits equals the recorded closing. This month is still provisional.',
    async run(ctx) {
      const now = new Date()
      const period = `${now.getFullYear()}-${String(now.getMonth() + 1).padStart(2, '0')}`
      const report = (await ctx.post(`/reconciliations/month-end?period=${period}`)).json
      ctx.expect(`month-end for ${period} is CLEAN`, report?.status, 'CLEAN')
      ctx.expect('provisional: the month is not over', report?.provisional, true)
      ctx.expect('every account reconciles', report?.accounts?.every((line: { matches: boolean }) => line.matches), true)
    },
  },
]
