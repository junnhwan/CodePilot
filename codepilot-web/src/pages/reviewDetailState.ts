import type { ReviewSession, ReviewTask, SseEvent } from '../types/index.ts';

const MAX_EVENTS = 100;

export interface ReviewDetailState {
  session: ReviewSession | null;
  events: SseEvent[];
  tasks: Record<string, ReviewTask>;
  seenEventKeys: Record<string, true>;
}

export function createReviewDetailState(session: ReviewSession | null): ReviewDetailState {
  return {
    session,
    events: [],
    tasks: {},
    seenEventKeys: {},
  };
}

export function mergeFetchedSession(
  state: ReviewDetailState,
  fetchedSession: ReviewSession,
): ReviewDetailState {
  const currentSession = state.session;
  if (!currentSession) {
    return {
      ...state,
      session: fetchedSession,
    };
  }

  return {
    ...state,
    session: {
      ...fetchedSession,
      state: currentSession.state,
      partial: currentSession.partial || fetchedSession.partial,
      findingCount: Math.max(currentSession.findingCount, fetchedSession.findingCount),
      completedAt: currentSession.completedAt ?? fetchedSession.completedAt,
    },
  };
}

export function applyReviewEvent(
  state: ReviewDetailState,
  event: SseEvent,
): ReviewDetailState {
  const eventKey = getEventKey(event);
  if (state.seenEventKeys[eventKey]) {
    return state;
  }

  const nextState: ReviewDetailState = {
    ...state,
    events: [event, ...state.events].slice(0, MAX_EVENTS),
    seenEventKeys: {
      ...state.seenEventKeys,
      [eventKey]: true,
    },
  };

  switch (event.type) {
    case 'session_created':
      return {
        ...nextState,
        session: nextState.session
          ? { ...nextState.session, state: 'PLANNING' }
          : nextState.session,
      };
    case 'plan_ready':
      return {
        ...nextState,
        session: nextState.session
          ? { ...nextState.session, state: 'REVIEWING' }
          : nextState.session,
      };
    case 'task_started':
      return {
        ...nextState,
        tasks: {
          ...nextState.tasks,
          [event.data.taskId]: createTask({
            taskId: event.data.taskId,
            type: event.data.type || 'UNKNOWN',
            state: 'IN_PROGRESS',
          }),
        },
      };
    case 'finding_found': {
      const currentTask = nextState.tasks[event.data.taskId]
        ?? createTask({
          taskId: event.data.taskId,
          state: 'IN_PROGRESS',
        });
      return {
        ...nextState,
        tasks: {
          ...nextState.tasks,
          [event.data.taskId]: {
            ...currentTask,
            findingCount: currentTask.findingCount + 1,
          },
        },
      };
    }
    case 'task_completed': {
      const currentTask = nextState.tasks[event.data.taskId]
        ?? createTask({
          taskId: event.data.taskId,
          type: event.data.type || 'UNKNOWN',
        });
      return {
        ...nextState,
        tasks: {
          ...nextState.tasks,
          [event.data.taskId]: {
            ...currentTask,
            type: event.data.type || currentTask.type,
            state: 'COMPLETED',
            findingCount: event.data.findingCount ?? currentTask.findingCount,
            partial: !!event.data.partial,
          },
        },
      };
    }
    case 'review_completed':
      return {
        ...nextState,
        session: nextState.session
          ? {
              ...nextState.session,
              state: 'DONE',
              partial: !!event.data.partial,
              findingCount: event.data.findingCount,
              completedAt: event.timestamp,
            }
          : nextState.session,
      };
    case 'review_failed':
      return {
        ...nextState,
        session: nextState.session
          ? {
              ...nextState.session,
              state: 'FAILED',
            }
          : nextState.session,
      };
    default:
      return nextState;
  }
}

export function getFindingsTotal(tasks: Record<string, ReviewTask>): number {
  return Object.values(tasks).reduce((total, task) => total + task.findingCount, 0);
}

export function getStats(tasks: Record<string, ReviewTask>, findingsTotal: number): {
  progress: number;
  activeAgents: number;
  findingDensity: string;
} {
  const allTasks = Object.values(tasks);
  const completedTasks = allTasks.filter((task) => task.state === 'COMPLETED').length;
  const totalTasks = allTasks.length;

  return {
    progress: totalTasks > 0 ? Math.round((completedTasks / totalTasks) * 100) : 0,
    activeAgents: allTasks.filter((task) => task.state === 'IN_PROGRESS').length,
    findingDensity: totalTasks > 0 ? (findingsTotal / totalTasks).toFixed(1) : '0',
  };
}

export function getTaskStatusLabel(task: ReviewTask): string {
  if (task.state === 'IN_PROGRESS') {
    return 'WORKING...';
  }
  if (task.partial) {
    return 'PARTIAL';
  }
  return 'COMPLETED';
}

export function getTaskStatusColor(task: ReviewTask): string {
  if (task.state === 'IN_PROGRESS') {
    return 'var(--brand-azure)';
  }
  if (task.partial) {
    return 'var(--brand-warm)';
  }
  return 'var(--brand-success)';
}

function createTask(overrides: Partial<ReviewTask>): ReviewTask {
  return {
    taskId: overrides.taskId ?? 'unknown-task',
    type: overrides.type ?? 'UNKNOWN',
    findingCount: overrides.findingCount ?? 0,
    partial: overrides.partial ?? false,
    state: overrides.state ?? 'COMPLETED',
  };
}

export function getEventKey(event: SseEvent): string {
  switch (event.type) {
    case 'session_created':
      return `session_created:${event.data.sessionId}:${event.data.prUrl}`;
    case 'plan_ready':
      return `plan_ready:${event.data.planId}:${event.data.taskCount}:${event.data.strategy}`;
    case 'task_started':
      return `task_started:${event.data.taskId}:${event.data.type}:${event.data.files.join(',')}`;
    case 'finding_found':
      return `finding_found:${event.data.taskId}:${event.data.title}:${event.data.severity}:${event.data.file}:${event.data.line}`;
    case 'task_completed':
      return `task_completed:${event.data.taskId}:${event.data.type}:${event.data.findingCount}:${event.data.partial}`;
    case 'review_completed':
      return `review_completed:${event.data.sessionId}:${event.data.findingCount}:${event.data.partial}`;
    case 'review_failed':
      return `review_failed:${event.data.sessionId}:${event.data.reason ?? ''}`;
    default:
      return JSON.stringify(event);
  }
}
