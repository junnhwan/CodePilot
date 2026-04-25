import assert from 'node:assert/strict';
import test from 'node:test';

import type { ReviewSession, SseEvent, ReviewTask } from '../types/index.ts';
import {
  applyReviewEvent,
  createReviewDetailState,
  getTaskStatusLabel,
} from './reviewDetailState.ts';

function createSession(overrides: Partial<ReviewSession> = {}): ReviewSession {
  return {
    sessionId: 'session-1',
    projectId: 'junnhwan/codepilot_test_repo',
    prNumber: 1,
    prUrl: 'https://github.com/junnhwan/codepilot_test_repo/pull/1',
    state: 'REVIEWING',
    partial: false,
    findingCount: 0,
    createdAt: '2026-04-25T10:20:29.755Z',
    ...overrides,
  };
}

function createTask(overrides: Partial<ReviewTask> = {}): ReviewTask {
  return {
    taskId: 'task-security',
    type: 'SECURITY',
    findingCount: 0,
    partial: false,
    state: 'COMPLETED',
    ...overrides,
  };
}

test('deduplicates repeated finding events with different timestamps', () => {
  const firstEvent: SseEvent = {
    type: 'finding_found',
    data: {
      taskId: 'task-perf',
      title: 'N+1 repository query introduced',
      severity: 'HIGH',
      file: 'src/main/java/com/example/orderhub/service/OrderSyncService.java',
      line: 44,
    },
    timestamp: '2026-04-25T10:21:43.899Z',
  };
  const duplicateEvent: SseEvent = {
    ...firstEvent,
    timestamp: '2026-04-25T10:23:36.204Z',
  };

  const initialState = createReviewDetailState(createSession());
  const withFirstEvent = applyReviewEvent(initialState, firstEvent);
  const withDuplicateEvent = applyReviewEvent(withFirstEvent, duplicateEvent);

  assert.equal(withDuplicateEvent.events.length, 1);
  assert.equal(withDuplicateEvent.tasks['task-perf']?.findingCount, 1);
});

test('syncs partial flag and finding count when review completes', () => {
  const initialState = createReviewDetailState(createSession());
  const completedEvent: SseEvent = {
    type: 'review_completed',
    data: {
      sessionId: 'session-1',
      findingCount: 4,
      partial: true,
    },
    timestamp: '2026-04-25T10:23:41.066Z',
  };

  const nextState = applyReviewEvent(initialState, completedEvent);

  assert.equal(nextState.session?.state, 'DONE');
  assert.equal(nextState.session?.partial, true);
  assert.equal(nextState.session?.findingCount, 4);
  assert.equal(nextState.session?.completedAt, '2026-04-25T10:23:41.066Z');
});

test('shows partial task state explicitly in the UI label', () => {
  assert.equal(getTaskStatusLabel(createTask({ partial: true })), 'PARTIAL');
  assert.equal(getTaskStatusLabel(createTask({ partial: false })), 'COMPLETED');
  assert.equal(getTaskStatusLabel(createTask({ state: 'IN_PROGRESS' })), 'WORKING...');
});
