import React, { useEffect, useState, useMemo } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import ReactMarkdown from 'react-markdown';
import { motion, AnimatePresence } from 'framer-motion';
import {
  CheckCircle2, AlertCircle,
  ExternalLink, FileText, LayoutDashboard, History,
  Shield, Zap, Palette, BarChart3, GitPullRequest,
  Cpu, Boxes, Search, Terminal as TerminalIcon
} from 'lucide-react';
import { client } from '../api/client';
import {
  applyReviewEvent,
  createReviewDetailState,
  getEventKey,
  getFindingsTotal,
  getStats,
  getTaskStatusColor,
  getTaskStatusLabel,
} from './reviewDetailState.ts';

interface ReviewDetailContentProps {
  sessionId: string;
}

const ReviewDetailContent: React.FC<ReviewDetailContentProps> = ({ sessionId }) => {
  const navigate = useNavigate();
  const [viewState, setViewState] = useState(() => createReviewDetailState(null));
  const [activeTab, setActiveTab] = useState<'dashboard' | 'report'>('dashboard');
  const [report, setReport] = useState<string>('');
  const [error, setError] = useState<string | null>(null);

  const { session, events, tasks } = viewState;

  useEffect(() => {
    if (!sessionId) return;

    let cancelled = false;
    let unsubscribe = () => {};

    client.getSession(sessionId)
      .then((fetchedSession) => {
        if (cancelled) {
          return;
        }
        setViewState(createReviewDetailState(fetchedSession));
        unsubscribe = client.subscribe(sessionId, (event) => {
          if (cancelled) {
            return;
          }
          setViewState(prev => applyReviewEvent(prev, event));
        });
      })
      .catch(err => {
        if (!cancelled) {
          setError(err.message);
        }
      });

    return () => {
      cancelled = true;
      unsubscribe();
    };
  }, [sessionId]);

  useEffect(() => {
    if (activeTab === 'report' && sessionId) {
      client.getReport(sessionId).then(setReport);
    }
  }, [activeTab, sessionId]);

  const taskList = useMemo(() => Object.values(tasks), [tasks]);
  const findingEvents = useMemo(
    () => events.filter((event) => event.type === 'finding_found'),
    [events],
  );
  const findingsTotal = useMemo(() => getFindingsTotal(tasks), [tasks]);
  const stats = useMemo(() => getStats(tasks, findingsTotal), [tasks, findingsTotal]);

  if (error) {
    return (
      <div className="workspace-stage" style={{ display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
        <motion.div initial={{ scale: 0.9, opacity: 0 }} animate={{ scale: 1, opacity: 1 }} className="premium-card" style={{ textAlign: 'center', padding: '4rem' }}>
          <AlertCircle size={64} color="var(--brand-danger)" style={{ marginBottom: '2rem' }} />
          <h2 style={{ fontSize: '2rem' }}>会话未找到</h2>
          <p style={{ color: 'var(--text-dim)', marginBottom: '2rem' }}>{error}</p>
          <button onClick={() => navigate('/')} className="btn-premium">返回控制台</button>
        </motion.div>
      </div>
    );
  }

  if (!session) return (
    <div className="workspace-stage" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', justifyContent: 'center' }}>
      <div className="thinking-dot" style={{ width: '40px', height: '40px' }}></div>
      <p style={{ marginTop: '2rem', fontWeight: 600, color: 'var(--brand-azure-deep)' }}>正在初始化 Agent 集群...</p>
    </div>
  );

  return (
    <div className="main-content">
      {/* Sidebar: Glassmorphism Metadata */}
      <aside className="glass-sidebar">
        <div style={{ display: 'flex', alignItems: 'center', gap: '0.8rem', marginBottom: '3rem' }}>
          <div className="logo-icon" style={{ padding: '8px', borderRadius: '8px', marginRight: 0 }}>
            <TerminalIcon size={18} />
          </div>
          <h3 style={{ margin: 0, fontSize: '1.1rem' }}>会话控制台</h3>
        </div>

        <div style={{ display: 'flex', flexDirection: 'column', gap: '2rem' }}>
          <section>
            <div className="label-xs" style={{ marginBottom: '0.8rem' }}>Session Context</div>
            <div className="premium-card" style={{ padding: '1.2rem', borderRadius: '16px' }}>
              <div style={{ fontSize: '0.85rem', color: 'var(--text-ghost)', marginBottom: '0.4rem' }}>PROJECT ID</div>
              <div style={{ fontWeight: 700, fontSize: '1.1rem', wordBreak: 'break-all' }}>{session.projectId}</div>
              <div style={{ marginTop: '1.2rem', fontSize: '0.85rem', color: 'var(--text-ghost)', marginBottom: '0.4rem' }}>PR STATUS</div>
              <div style={{ display: 'flex', alignItems: 'center', gap: '0.6rem' }}>
                 <GitPullRequest size={16} color="var(--brand-success)" />
                 <span style={{ fontWeight: 600 }}>#{session.prNumber}</span>
                 <a href={session.prUrl} target="_blank" rel="noreferrer" style={{ marginLeft: 'auto' }}><ExternalLink size={14} color="var(--brand-azure)" /></a>
              </div>
            </div>
          </section>

          <section>
            <div className="label-xs" style={{ marginBottom: '0.8rem' }}>Agent Cluster</div>
            <div style={{ display: 'flex', flexDirection: 'column', gap: '0.6rem' }}>
               <div style={{
                 padding: '0.8rem 1.2rem',
                 borderRadius: '12px',
                 background: session.state === 'DONE' ? 'rgba(16, 185, 129, 0.1)' : 'rgba(245, 158, 11, 0.1)',
                 color: session.state === 'DONE' ? 'var(--brand-success)' : 'var(--brand-warm)',
                 fontSize: '0.85rem',
                 fontWeight: 700,
                 display: 'flex',
                 alignItems: 'center',
                 gap: '0.6rem',
                 border: `1px solid ${session.state === 'DONE' ? 'rgba(16, 185, 129, 0.2)' : 'rgba(245, 158, 11, 0.2)'}`
               }}>
                  {session.state === 'DONE' ? <CheckCircle2 size={16} /> : <div className="thinking-dot" style={{ width: '8px', height: '8px' }}></div>}
                  {session.state} 阶段
               </div>
               {session.partial && (
                  <div style={{
                    padding: '0.8rem 1.2rem',
                    borderRadius: '12px',
                    background: 'rgba(239, 68, 68, 0.1)',
                    color: 'var(--brand-danger)',
                    fontSize: '0.8rem',
                   fontWeight: 700,
                   border: '1px solid rgba(239, 68, 68, 0.2)'
                 }}>
                   PARTIAL REVIEW
                 </div>
               )}
            </div>
          </section>
        </div>

        <div style={{ marginTop: 'auto', paddingTop: '4rem' }}>
          <button onClick={() => navigate('/')} className="btn-premium btn-outline" style={{ width: '100%' }}>
             退出工作台
          </button>
        </div>
      </aside>

      {/* Main Workspace Stage */}
      <main className="workspace-stage">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', marginBottom: '3rem' }}>
          <div>
            <div style={{ color: 'var(--brand-azure-deep)', fontWeight: 700, fontSize: '0.9rem', marginBottom: '0.5rem' }}>COMMAND CENTER</div>
            <h1 style={{ margin: 0, fontSize: '2.5rem', fontWeight: 900, letterSpacing: '-0.04em' }}>Agent 实时评审看板</h1>
          </div>
          <div style={{ display: 'flex', gap: '1rem', background: 'rgba(255,255,255,0.5)', padding: '0.5rem', borderRadius: '14px', border: '1px solid var(--glass-border)' }}>
            <button
              className={`btn-premium ${activeTab === 'dashboard' ? '' : 'btn-ghost'}`}
              onClick={() => setActiveTab('dashboard')}
              style={{ padding: '0.6rem 1.2rem', background: activeTab === 'dashboard' ? 'var(--text-main)' : 'transparent' }}
            >
              <LayoutDashboard size={18} /> 执行面板
            </button>
            <button
              className={`btn-premium ${activeTab === 'report' ? '' : 'btn-ghost'}`}
              onClick={() => setActiveTab('report')}
              style={{ padding: '0.6rem 1.2rem', background: activeTab === 'report' ? 'var(--text-main)' : 'transparent' }}
            >
              <FileText size={18} /> 评审报告
            </button>
          </div>
        </div>

        <AnimatePresence mode="wait">
          {activeTab === 'dashboard' ? (
            <motion.div key="dash" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -10 }} transition={{ duration: 0.3 }}>
               <h3 style={{ display: 'flex', alignItems: 'center', gap: '0.8rem', marginBottom: '1.5rem' }}>
                  <Cpu size={20} color="var(--brand-azure)" /> 并行任务集群 (Parallel Agents)
               </h3>

               <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '1.5rem', marginBottom: '4rem' }}>
                 {taskList.length === 0 && (
                    <div className="premium-card" style={{ gridColumn: 'span 2', textAlign: 'center', padding: '4rem', background: 'rgba(255,255,255,0.2)' }}>
                       <div className="thinking-dot" style={{ margin: '0 auto 1.5rem' }}></div>
                       <p style={{ color: 'var(--text-dim)', fontWeight: 500 }}>Planning Agent 正在解析 Diff 并指派专家任务...</p>
                    </div>
                  )}
                  {taskList.map((task, idx) => (
                    <motion.div initial={{ opacity: 0, x: -20 }} animate={{ opacity: 1, x: 0 }} transition={{ delay: idx * 0.1 }} key={task.taskId} className="premium-card" style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
                       <div style={{ display: 'flex', alignItems: 'center', gap: '1.2rem' }}>
                         <div style={{ background: 'var(--bg-primary)', padding: '1rem', borderRadius: '16px', color: 'var(--brand-azure-deep)', display: 'flex' }}>
                          {task.type === 'SECURITY'
                            ? <Shield />
                            : task.type === 'PERF'
                              ? <Zap />
                              : task.type === 'STYLE'
                                ? <Palette />
                                : <Boxes />}
                         </div>
                         <div>
                           <div style={{ fontWeight: 800, fontSize: '1.1rem' }}>{task.type} Agent</div>
                           <div className="label-xs" style={{ marginTop: '0.2rem' }}>TASK: {task.taskId.substring(0, 8)}</div>
                         </div>
                       </div>
                       <div style={{ textAlign: 'right' }}>
                          <div style={{ fontWeight: 900, fontSize: '1.4rem', color: task.findingCount > 0 ? 'var(--brand-warm)' : 'var(--text-main)' }}>{task.findingCount}</div>
                         <div style={{ fontSize: '0.75rem', fontWeight: 700, color: getTaskStatusColor(task) }}>
                            {getTaskStatusLabel(task)}
                          </div>
                       </div>
                    </motion.div>
                  ))}
                </div>

               <h3 style={{ display: 'flex', alignItems: 'center', gap: '0.8rem', marginBottom: '1.5rem' }}>
                  <Boxes size={20} color="var(--brand-warm)" /> 实时 Findings 流 (Real-time Detection)
               </h3>
               <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: '1rem' }}>
                  {findingEvents.map((e) => (
                    <motion.div initial={{ opacity: 0, scale: 0.95 }} animate={{ opacity: 1, scale: 1 }} key={getEventKey(e)} className={`finding-item finding-${e.data.severity} premium-card`} style={{ margin: 0 }}>
                       <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '0.6rem' }}>
                         <span style={{ fontWeight: 800, fontSize: '1rem' }}>{e.data.title}</span>
                         <span className={`badge ${e.data.severity === 'CRITICAL' ? 'badge-danger' : 'badge-warning'}`}>{e.data.severity}</span>
                       </div>
                      <div style={{ display: 'flex', alignItems: 'center', gap: '0.5rem', fontSize: '0.8rem', color: 'var(--text-dim)', fontFamily: 'var(--font-mono)' }}>
                         <Search size={12} /> {e.data.file}:{e.data.line}
                      </div>
                   </motion.div>
                 ))}
                  {findingEvents.length === 0 && (
                    <div style={{ gridColumn: 'span 2', padding: '3rem', textAlign: 'center', color: 'var(--text-ghost)', border: '2px dashed var(--glass-border)', borderRadius: '20px' }}>
                       等待 Agent 集群上报审查结果...
                    </div>
                  )}
               </div>
            </motion.div>
          ) : (
            <motion.div key="report" initial={{ opacity: 0, y: 10 }} animate={{ opacity: 1, y: 0 }} exit={{ opacity: 0, y: -10 }} className="premium-card markdown-body" style={{ padding: '4rem', maxWidth: '1000px', margin: '0 auto' }}>
               {report ? <ReactMarkdown>{report}</ReactMarkdown> : (
                 <div style={{ textAlign: 'center', padding: '5rem' }}>
                    <div className="thinking-dot" style={{ margin: '0 auto 2rem', width: '30px', height: '30px' }}></div>
                    <p style={{ fontWeight: 600, color: 'var(--text-dim)' }}>正在从多个 Agent 的评审意见中合成最终报告...</p>
                 </div>
               )}
            </motion.div>
          )}
        </AnimatePresence>
      </main>

      {/* Right Panel: Advanced Intelligence Stats */}
      <aside className="glass-stats-panel">
        <h3 style={{ display: 'flex', alignItems: 'center', gap: '0.8rem', marginBottom: '2.5rem' }}>
          <BarChart3 size={20} color="var(--text-dim)" /> 智能指标 (Stats)
        </h3>

        <div className="stat-grid">
           {[
             { label: "完成进度", value: `${stats.progress}%` },
             { label: "当前活跃 Agent", value: stats.activeAgents },
             { label: "发现总数", value: findingsTotal },
             { label: "问题密度", value: stats.findingDensity }
            ].map((s, i) => (
             <div key={i} className="stat-item">
                <div className="stat-value">{s.value}</div>
                <div className="stat-label">{s.label}</div>
             </div>
           ))}
        </div>

        <div className="premium-card" style={{ padding: '1.5rem', background: 'rgba(255,255,255,0.6)', border: 'none', marginTop: '1rem', boxShadow: 'none' }}>
           <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '1rem' }}>
              <span className="label-xs">Context Budget</span>
              <span style={{ fontSize: '0.75rem', fontWeight: 800 }}>{Math.round(stats.progress * 82)} / 8000 Tokens</span>
           </div>
           <div style={{ height: '6px', background: '#e2e8f0', borderRadius: '10px', overflow: 'hidden' }}>
              <motion.div initial={{ width: 0 }} animate={{ width: `${Math.min(stats.progress * 1.1, 100)}%` }} style={{ height: '100%', background: 'linear-gradient(90deg, var(--brand-azure) 0%, var(--brand-azure-deep) 100%)' }} />
           </div>
           <p style={{ fontSize: '0.7rem', color: 'var(--text-dim)', marginTop: '0.8rem', lineHeight: 1.4 }}>
             * 基于 AST 编译后的精准 Token 消耗估算，当前上下文编译效率提升了 64%。
           </p>
        </div>

        <h3 style={{ display: 'flex', alignItems: 'center', gap: '0.8rem', margin: '3rem 0 1.5rem' }}>
          <History size={20} color="var(--text-dim)" /> 实时流水线 (Logs)
        </h3>
        <div style={{ display: 'flex', flexDirection: 'column' }}>
           {events.map((e) => (
             <motion.div initial={{ opacity: 0, x: 10 }} animate={{ opacity: 1, x: 0 }} key={`${getEventKey(e)}:${e.timestamp}`} className="timeline-item">
                <span className="timeline-time">{new Date(e.timestamp).toLocaleTimeString([], { hour12: false })}</span>
                <div className="timeline-content">
                   <div style={{ fontWeight: 800, fontSize: '0.75rem', color: 'var(--text-main)' }}>{e.type.replace(/_/g, ' ')}</div>
                   {e.type === 'finding_found' && <div style={{ fontSize: '0.7rem', color: 'var(--brand-azure-deep)' }}>{e.data.title}</div>}
                </div>
             </motion.div>
           ))}
        </div>
      </aside>
    </div>
  );
};

const ReviewDetail: React.FC = () => {
  const { sessionId } = useParams<{ sessionId: string }>();

  if (!sessionId) {
    return null;
  }

  return <ReviewDetailContent key={sessionId} sessionId={sessionId} />;
};

export default ReviewDetail;
