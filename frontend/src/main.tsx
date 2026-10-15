import '@mantine/core/styles.css'
import '@mantine/notifications/styles.css'
import './global.css'

import { MantineProvider } from '@mantine/core'
import { Notifications } from '@mantine/notifications'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'

import { ApiProblem } from './api/problem'
import App from './App.tsx'
import { theme } from './theme.ts'

/**
 * Retry only while the backend is unreachable (it may still be starting); a real answer, even an error,
 * is shown at once.
 */
const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: (failures, error) =>
        error instanceof ApiProblem && error.code === 'BACKEND_UNREACHABLE' && failures < 10,
      retryDelay: 3000,
    },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <MantineProvider theme={theme} defaultColorScheme="auto">
      <Notifications position="top-right" />
      <QueryClientProvider client={queryClient}>
        <BrowserRouter>
          <App />
        </BrowserRouter>
      </QueryClientProvider>
    </MantineProvider>
  </StrictMode>,
)
