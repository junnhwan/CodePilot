import React, { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { motion } from 'framer-motion';
import { Search, Play, Shield, Zap, Database, Cpu } from 'lucide-react';

const Home: React.FC = () => {
  const [sessionId, setSessionId] = useState('');
  const navigate = useNavigate();

  const handleLookup = (e: React.FormEvent) => {
    e.preventDefault();
    if (sessionId.trim()) {
      navigate(`/review/${sessionId.trim()}`);
    }
  };

  const features = [
    { icon: <Shield size={28} />, title: "安全专家级审计", desc: "不仅是代码扫描，我们深度理解 SQL 注入与业务鉴权漏洞。" },
    { icon: <Cpu size={28} />, title: "AST 上下文感知", desc: "精准提取方法级调用链，跨文件追踪逻辑变更。" },
    { icon: <Database size={28} />, title: "三层项目记忆", desc: "自动沉淀团队规范，让 AI 越评审越像您的队友。" },
    { icon: <Zap size={28} />, title: "并行 Agent 调度", desc: "秒级响应，多个专项专家 Agent 同时为您服务。" }
  ];

  return (
    <div className="workspace-stage" style={{ display: 'flex', flexDirection: 'column', alignItems: 'center' }}>

      {/* Hero Section */}
      <section style={{ width: '100%', maxWidth: '1400px', padding: '8rem 0', position: 'relative' }}>
        <div style={{ maxWidth: '900px' }}>
          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6 }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '1rem', marginBottom: '2rem' }}>
              <span style={{ height: '1px', width: '40px', background: '#cbd5e1' }}></span>
              <span className="label-xs" style={{ color: 'var(--brand-azure-deep)' }}>NEXT-GEN AI CODE REVIEW AGENT</span>
            </div>
            <h1 className="hero-title">
              Deep Code <br />
              <span style={{ color: 'var(--brand-azure)' }}>Intelligence.</span>
            </h1>
          </motion.div>

          <motion.p
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.2 }}
            className="hero-subtitle"
          >
            不仅是静态扫描。我们通过 <strong>AST 上下文编译</strong> 和 <strong>三层项目记忆</strong>，让 AI 像资深架构师一样理解您的代码逻辑。
          </motion.p>

          <motion.div
            initial={{ opacity: 0, y: 20 }}
            animate={{ opacity: 1, y: 0 }}
            transition={{ duration: 0.6, delay: 0.4 }}
            style={{ marginTop: '4rem', display: 'flex', gap: '1.5rem', alignItems: 'center' }}
          >
            <form onSubmit={handleLookup} className="search-container">
              <input
                type="text"
                className="input-premium"
                placeholder="输入 Session ID..."
                value={sessionId}
                onChange={(e) => setSessionId(e.target.value)}
              />
              <button type="submit" className="btn-premium" style={{ padding: '0.6rem 1.2rem' }}>
                <Search size={20} />
              </button>
            </form>

            <button
              onClick={() => navigate(`/review/mock-${Math.random().toString(36).substring(7)}?mock=true`)}
              className="btn-premium btn-outline"
              style={{ padding: '0.6rem 1.5rem' }}
            >
              <Play size={18} /> 快速演示 Demo
            </button>
          </motion.div>
        </div>

        {/* Decorative Floating Code Card */}
        <motion.div
          initial={{ opacity: 0, x: 50 }}
          animate={{ opacity: 1, x: 0 }}
          transition={{ duration: 1, delay: 0.5 }}
          className="floating-code-card"
        >
          <div className="premium-card code-card-inner">
            <div className="code-header">
              <div className="window-dot" style={{ background: '#ff5f56' }}></div>
              <div className="window-dot" style={{ background: '#ffbd2e' }}></div>
              <div className="window-dot" style={{ background: '#27c93f' }}></div>
              <span style={{ marginLeft: 'auto', color: '#64748b', fontSize: '0.7rem', fontWeight: 700 }}>AST_COMPILER.java</span>
            </div>
            <pre className="code-content">
              <code>
                <span style={{ color: '#94a3b8' }}>// Calculating impact scope...</span><br />
                <span style={{ color: '#38bdf8' }}>ImpactSet</span> scope = compiler.<span style={{ color: '#f59e0b' }}>analyze</span>(diff);<br />
                <span style={{ color: '#f59e0b' }}>if</span> (scope.contains(<span style={{ color: '#10b981' }}>"Security"</span>)) &#123;<br />
                &nbsp;&nbsp;agents.<span style={{ color: '#38bdf8' }}>dispatch</span>(new <span style={{ color: '#ef4444' }}>SecurityReviewer</span>());<br />
                &#125;
              </code>
            </pre>
            <div className="code-footer">
              <div className="thinking-dot"></div>
              <span style={{ fontSize: '0.85rem', fontWeight: 600, color: 'var(--brand-azure-deep)' }}>AI 正在按需编译上下文...</span>
            </div>
          </div>
        </motion.div>
      </section>

      {/* Feature Grid */}
      <section style={{ width: '100%', maxWidth: '1400px', display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: '2rem', paddingBottom: '10rem' }}>
        {features.map((feat, i) => (
          <motion.div
            key={i}
            initial={{ opacity: 0, y: 20 }}
            whileInView={{ opacity: 1, y: 0 }}
            viewport={{ once: true }}
            transition={{ duration: 0.5, delay: i * 0.1 }}
            className="premium-card"
            style={{ display: 'flex', flexDirection: 'column', gap: '1.5rem' }}
          >
            <div style={{ color: 'var(--brand-azure-deep)', background: 'rgba(56, 189, 248, 0.1)', width: 'fit-content', padding: '1rem', borderRadius: '16px', display: 'flex' }}>
              {feat.icon}
            </div>
            <div>
              <h3 style={{ margin: '0 0 0.5rem', fontSize: '1.2rem' }}>{feat.title}</h3>
              <p style={{ margin: 0, color: 'var(--text-dim)', fontSize: '0.95rem', lineHeight: 1.6 }}>{feat.desc}</p>
            </div>
          </motion.div>
        ))}
      </section>
    </div>
  );
};

export default Home;
