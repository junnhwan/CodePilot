import { BrowserRouter as Router, Routes, Route, Link } from 'react-router-dom';
import { Terminal, Book } from 'lucide-react';
import Home from './pages/Home';
import ReviewDetail from './pages/ReviewDetail';
import './styles/App.css';

function App() {
  return (
    <Router>
      <div className="app-layout">
        {/* Decorative Backgrounds */}
        <div className="mesh-gradient" />
        <div className="noise-overlay" />

        <header className="glass-header">
          <Link to="/" className="logo">
            <div className="logo-icon">
              <Terminal size={20} />
            </div>
            <span className="logo-text">CodePilot</span>
            <span className="logo-tagline">
              Agentic Code Review
            </span>
          </Link>

          <div className="nav-container">
            <nav className="nav-links">
              <Link to="/" className="nav-link">控制台</Link>
              <Link to="/" className="nav-link">架构设计</Link>
            </nav>
            <Link to="/" className="btn-premium">
              <Book size={16} /> 使用文档
            </Link>
          </div>
        </header>

        <main className="main-content">
          <Routes>
            <Route path="/" element={<Home />} />
            <Route path="/review/:sessionId" element={<ReviewDetail />} />
          </Routes>
        </main>
      </div>
    </Router>
  );
}

export default App;
