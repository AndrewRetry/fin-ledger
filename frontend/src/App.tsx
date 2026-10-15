import { Route, Routes } from 'react-router'

import { AppLayout } from './components/AppLayout'
import { AdminPage } from './pages/AdminPage'
import { FxRatesPage } from './pages/FxRatesPage'
import { AccountDetailPage } from './pages/AccountDetailPage'
import { AccountsPage } from './pages/AccountsPage'
import { IntegrityPage } from './pages/IntegrityPage'
import { MoveMoneyPage } from './pages/MoveMoneyPage'
import { NotFoundPage } from './pages/NotFoundPage'
import { OverviewPage } from './pages/OverviewPage'
import { ScenariosPage } from './pages/ScenariosPage'
import { TransactionDetailPage } from './pages/TransactionDetailPage'
import { TransactionsPage } from './pages/TransactionsPage'

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
        <Route path="integrity" element={<IntegrityPage />} />
        <Route path="scenarios" element={<ScenariosPage />} />
        <Route path="admin" element={<AdminPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
