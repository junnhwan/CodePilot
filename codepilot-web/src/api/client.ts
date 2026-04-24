import type { ReviewSession, SseEvent } from '../types';

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL || '/api/v1';

export interface ApiClient {
  getSession(sessionId: string): Promise<ReviewSession>;
  getReport(sessionId: string): Promise<string>;
  subscribe(sessionId: string, onEvent: (event: SseEvent) => void): () => void;
}

class RealApiClient implements ApiClient {
  async getSession(sessionId: string): Promise<ReviewSession> {
    const response = await fetch(`${API_BASE_URL}/reviews/${sessionId}`);
    if (!response.ok) throw new Error('Session not found');
    return response.json();
  }

  async getReport(sessionId: string): Promise<string> {
    const response = await fetch(`${API_BASE_URL}/reviews/${sessionId}/report`);
    if (!response.ok) throw new Error('Report not found');
    return response.text();
  }

  subscribe(sessionId: string, onEvent: (event: SseEvent) => void): () => void {
    const eventSource = new EventSource(`${API_BASE_URL}/reviews/${sessionId}/stream`);

    const handler = (type: SseEvent['type']) => (e: MessageEvent) => {
      onEvent({
        type,
        data: JSON.parse(e.data),
        timestamp: new Date().toISOString()
      } as SseEvent);
    };

    const eventTypes: SseEvent['type'][] = [
      'session_created', 'plan_ready', 'task_started',
      'finding_found', 'task_completed', 'review_completed', 'review_failed'
    ];

    eventTypes.forEach(type => {
      eventSource.addEventListener(type, handler(type));
    });

    return () => eventSource.close();
  }
}

class MockApiClient implements ApiClient {
  private sessions: Record<string, ReviewSession> = {
    'mock-123': {
      sessionId: 'mock-123',
      projectId: 'codepilot-demo',
      prNumber: 42,
      prUrl: 'https://github.com/codepilot/demo/pull/42',
      state: 'DONE',
      partial: false,
      findingCount: 2,
      createdAt: new Date(Date.now() - 300000).toISOString(),
      completedAt: new Date().toISOString()
    }
  };

  async getSession(sessionId: string): Promise<ReviewSession> {
    await new Promise(r => setTimeout(r, 500));
    return this.sessions[sessionId] || {
      sessionId,
      projectId: 'codepilot-mock-project',
      prNumber: 99,
      prUrl: 'https://github.com/demo/repo/pull/99',
      state: 'REVIEWING',
      partial: false,
      findingCount: 0,
      createdAt: new Date().toISOString()
    };
  }

  async getReport(): Promise<string> {
    await new Promise(r => setTimeout(r, 800));
    return `# CodePilot Review Report
> Findings below are reported by the current reviewer run and are not issue-confirmed.

## Potential finding: SQL Injection Risk
- Severity: CRITICAL
- Confidence: 0.95
- Location: src/main/java/com/demo/UserService.java:45

The query string is built using direct string concatenation with user-provided input.

Suggestion: Use PreparedStatements or a safe ORM query builder.

## Potential finding: Inefficient N+1 Query
- Severity: MEDIUM
- Confidence: 0.85
- Location: src/main/java/com/demo/OrderController.java:112

Fetching orders inside a loop may lead to many database calls.

Suggestion: Use batch fetching or join queries.
`;
  }

  subscribe(sessionId: string, onEvent: (event: SseEvent) => void): () => void {
    const steps: { type: SseEvent['type']; data: SseEvent['data']; delay: number }[] = [
      { type: 'session_created', data: { sessionId, prUrl: 'https://github.com/demo/repo/pull/1' }, delay: 500 },
      { type: 'plan_ready', data: { planId: 'plan-1', taskCount: 2, strategy: 'COMPREHENSIVE' }, delay: 1500 },
      { type: 'task_started', data: { taskId: 'task-1', type: 'SECURITY', files: ['Auth.java'] }, delay: 2500 },
      { type: 'finding_found', data: { taskId: 'task-1', title: 'SQL Injection', severity: 'CRITICAL', file: 'Auth.java', line: 12 }, delay: 4000 },
      { type: 'task_completed', data: { taskId: 'task-1', type: 'SECURITY', findingCount: 1, partial: false }, delay: 6000 },
      { type: 'task_started', data: { taskId: 'task-2', type: 'PERF', files: ['DB.java'] }, delay: 7000 },
      { type: 'task_completed', data: { taskId: 'task-2', type: 'PERF', findingCount: 0, partial: false }, delay: 9000 },
      { type: 'review_completed', data: { sessionId, findingCount: 1, partial: false }, delay: 10500 },
    ];

    const timeouts: ReturnType<typeof setTimeout>[] = [];
    steps.forEach(step => {
      const t = setTimeout(() => {
        onEvent({
          type: step.type,
          data: step.data,
          timestamp: new Date().toISOString()
        } as SseEvent);
      }, step.delay);
      timeouts.push(t);
    });

    return () => timeouts.forEach(t => clearTimeout(t));
  }
}

const realClient = new RealApiClient();
const mockClient = new MockApiClient();

const isMock = () => import.meta.env.VITE_MOCK === 'true' || location.search.includes('mock=true') || location.pathname.includes('/mock-');

export const client: ApiClient = {
  getSession: (id) => (isMock() ? mockClient : realClient).getSession(id),
  getReport: (id) => (isMock() ? mockClient : realClient).getReport(id),
  subscribe: (id, cb) => (isMock() ? mockClient : realClient).subscribe(id, cb),
};
