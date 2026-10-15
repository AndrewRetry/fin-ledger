import { Route, Routes } from 'react-router'

import { AppLayout } from './components/AppLayout'
import { AdminPage } from './pages/AdminPage'
import { FxRatesPage } from './pages/FxRatesPage'
import { AccountDetailPage } from './pages/AccountDetailPage'
import { AccountsPage } from './pages/AccountsPage'
import { MoveMoneyPage } from './pages/MoveMoneyPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OverviewPage } from './pages/OverviewPage'
import { TransactionDetailPage } from './pages/TransactionDetailPage'
import { TransactionsPage } from './pages/TransactionsPage'
import { PlaceholderPage } from './pages/PlaceholderPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<OverviewPage />} />
        <Route path="accounts" element={<AccountsPage />} />
        <Route path="accounts/:id" element={<AccountDetailPage />} />
        <Route path="move" element={<MoveMoneyPage />} />
        <Route path="transactions" element={<TransactionsPage />} />
        <Route path="transactions/:id" element={<TransactionDetailPage />} />
        <Route path="fx" element={<FxRatesPage />} />
        <Route path="integrity" element={
          <PlaceholderPage title="Integrity" phase="F4">Integrity checks, stored runs and month-end reconciliation.</PlaceholderPage>} />
        <Route path="scenarios" element={
          <PlaceholderPage title="Scenarios" phase="F5">Every requirement of the assignment, runnable with one click.</PlaceholderPage>} />
        <Route path="admin" element={<AdminPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
