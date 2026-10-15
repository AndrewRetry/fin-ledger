import { Route, Routes } from 'react-router'

import { AppLayout } from './components/AppLayout'
import { AccountDetailPage } from './pages/AccountDetailPage'
import { AccountsPage } from './pages/AccountsPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OverviewPage } from './pages/OverviewPage'
import { PlaceholderPage } from './pages/PlaceholderPage'

export default function App() {
  return (
    <Routes>
      <Route element={<AppLayout />}>
        <Route index element={<OverviewPage />} />
        <Route path="accounts" element={<AccountsPage />} />
        <Route path="accounts/:id" element={<AccountDetailPage />} />
        <Route path="move" element={
          <PlaceholderPage title="Move money" phase="F2">Transfers, deposits and withdrawals, including FX.</PlaceholderPage>} />
        <Route path="transactions/*" element={
          <PlaceholderPage title="Transactions" phase="F2">Look up a transaction, verify its hash, reverse it.</PlaceholderPage>} />
        <Route path="fx" element={
          <PlaceholderPage title="FX rates" phase="F3">The rate in use for every currency pair, and adding one.</PlaceholderPage>} />
        <Route path="integrity" element={
          <PlaceholderPage title="Integrity" phase="F4">Integrity checks, stored runs and month-end reconciliation.</PlaceholderPage>} />
        <Route path="scenarios" element={
          <PlaceholderPage title="Scenarios" phase="F5">Every requirement of the assignment, runnable with one click.</PlaceholderPage>} />
        <Route path="admin" element={
          <PlaceholderPage title="Admin" phase="F3">The admin key, the integrity hold and its release.</PlaceholderPage>} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
