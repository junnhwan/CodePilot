# CodePilot Frontend MVP

这是一个独立的前端 MVP 项目，用于演示 CodePilot 的 review 过程和结果。

## 核心功能

1. **Session Lookup**：通过 Session ID 查询 review 状态。
2. **Live Review**：订阅 SSE 事件流，实时展示 Pipeline 进度和 Findings。
3. **Report Rendering**：渲染结构化的 Markdown 报告。
4. **Mock Mode**：支持离线演示模式，无需后端即可查看完整流程。

## 技术栈

- React 19
- TypeScript
- Vite
- React Router 7
- React Markdown
- Lucide React (Icons)
- Vanilla CSS

## 快速启动

### 1. 安装依赖
```bash
npm install
```

### 2. 启动开发服务器
```bash
npm run dev
```

### 3. 访问
- 默认地址：[http://localhost:5173](http://localhost:5173)
- 进入 Mock 演示：点击首页 "Try Demo" 按钮，或访问 `/?mock=true`

## 环境变量

在 `.env` 文件中配置：

- `VITE_API_BASE_URL`: 后端 Gateway 地址 (默认 `/api/v1`)
- `VITE_MOCK`: 设置为 `true` 强制开启 Mock 模式

## 目录结构

- `src/api/`: API 与 SSE 客户端，包含 Mock 实现。
- `src/pages/`: 首页与详情页。
- `src/types/`: 核心领域模型定义（与后端一致）。
- `src/styles/`: 全局样式。
