export type ReviewState = 'IDLE' | 'PLANNING' | 'REVIEWING' | 'MERGING' | 'REPORTING' | 'DONE' | 'FAILED';

export interface ReviewSession {
  sessionId: string;
  projectId: string;
  prNumber: number;
  prUrl: string;
  state: ReviewState;
  partial: boolean;
  findingCount: number;
  createdAt: string;
  completedAt?: string;
}

export interface Finding {
  findingId: string;
  taskId: string;
  category: string;
  severity: 'CRITICAL' | 'HIGH' | 'MEDIUM' | 'LOW' | 'INFO';
  confidence: number;
  file: string;
  line: number;
  title: string;
  description: string;
  suggestion?: string;
}

export interface ReviewTask {
  taskId: string;
  type: string;
  findingCount: number;
  partial: boolean;
  state: 'PENDING' | 'IN_PROGRESS' | 'COMPLETED' | 'FAILED';
}

export type SseEvent =
  | { type: 'session_created'; data: { sessionId: string; prUrl: string }; timestamp: string }
  | { type: 'plan_ready'; data: { planId: string; taskCount: number; strategy: string }; timestamp: string }
  | { type: 'task_started'; data: { taskId: string; type: string; files: string[] }; timestamp: string }
  | { type: 'finding_found'; data: { taskId: string; title: string; severity: Finding['severity']; file: string; line: number }; timestamp: string }
  | { type: 'task_completed'; data: { taskId: string; type: string; findingCount: number; partial: boolean }; timestamp: string }
  | { type: 'review_completed'; data: { sessionId: string; findingCount: number; partial: boolean }; timestamp: string }
  | { type: 'review_failed'; data: { sessionId: string; reason?: string }; timestamp: string };
