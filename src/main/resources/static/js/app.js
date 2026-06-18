/**
 * 国产数据库适配助手 - 前端逻辑
 * 两阶段工作流：分析阶段（只读+出方案）→ 执行阶段（确认后修改文件）
 */

const state = {
  currentSession: null,
  sessions: [],
  messages: [],
  diffs: [],
  isStreaming: false,
  sidebarOpen: true,
  processAlive: false,
  statusTimer: null
};

const API = '/api';

const PHASE_LABELS = {
  analysis:  { label: '分析中', color: '#60a5fa', icon: '🔍' },
  review:    { label: '待确认', color: '#fbbf24', icon: '📋' },
  execution: { label: '执行中', color: '#4ade80', icon: '⚡' },
  completed: { label: '已完成', color: '#a78bfa', icon: '✅' },
  terminated:{ label: '已终止', color: '#f87171', icon: '⛔' }
};

// ==================== 初始化 ====================
document.addEventListener('DOMContentLoaded', async () => {
  await checkStatus();
  await loadSessions();
  renderDbRefCards();
  document.getElementById('newSessionName').addEventListener('keydown', e => {
    if (e.key === 'Enter') createSession();
  });
  document.getElementById('tabConfig').addEventListener('click', () => {
    setTimeout(refreshCliStatus, 100);
  });

  // ==================== 启动验证功能 ====================

  /** 打开启动验证对话框 */
  function validateStartup() {
    if (!state.currentSession) { alert('请先选择会话'); return; }
    if (!state.currentSession.projectPath) {
      alert('请先在配置中设置项目路径');
      switchTab('config');
      return;
    }

    // 设置默认命令
    const projectPath = state.currentSession.projectPath;
    let defaultCommand = '';

    // 根据项目类型推断默认启动命令
    if (projectPath.includes('pom.xml') || projectPath.includes('pom.xml')) {
      defaultCommand = 'mvn spring-boot:run';
    } else if (projectPath.includes('build.gradle')) {
      defaultCommand = 'gradle bootRun';
    } else if (projectPath.includes('package.json')) {
      defaultCommand = 'npm start';
    } else if (projectPath.includes('.jar')) {
      // 查找jar文件
      const jarFiles = findJarFiles(projectPath);
      defaultCommand = jarFiles.length > 0 ? `java -jar ${jarFiles[0]}` : 'java -jar your-app.jar';
    } else {
      defaultCommand = '请输入启动命令';
    }

    document.getElementById('startupCommand').value = defaultCommand;
    document.getElementById('startupLog').style.display = 'none';
    document.getElementById('btnStartValidation').disabled = false;
    document.getElementById('btnStartValidation').textContent = '开始启动';
    openModal('modalStartup');
  }

  /** 查找项目中的jar文件 */
  function findJarFiles(projectPath) {
    // 简单实现，实际项目中可能需要更复杂的扫描
    const targetDir = projectPath;
    const jars = [];

    // 常见的jar位置
    const possiblePaths = [
      `${targetDir}/target/*.jar`,
      `${targetDir}/*.jar`,
      `${targetDir}/build/libs/*.jar`,
      `${targetDir}/dist/*.jar`
    ];

    // 这里只是模拟，实际使用时需要真实的文件系统访问
    return jars;
  }

  /** 执行启动验证 */
  async function doStartupValidation() {
    if (!state.currentSession) return;

    const command = document.getElementById('startupCommand').value.trim();
    if (!command) {
      alert('请输入启动命令');
      return;
    }

    const btn = document.getElementById('btnStartValidation');
    btn.disabled = true;
    btn.textContent = '启动中...';

    // 清空之前的日志
    const logContent = document.querySelector('.log-content');
    logContent.innerHTML = '';
    document.getElementById('startupLog').style.display = 'block';

    // 流式接收启动日志
    try {
      const response = await fetch(`${API}/sessions/${state.currentSession.id}/validate-startup`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ startupCommand: command })
      });

      if (!response.ok) {
        const err = await response.json().catch(() => ({ error: '请求失败' }));
        throw new Error(err.error || '启动验证失败');
      }

      const reader = response.body.getReader();
      const decoder = new TextDecoder();

      while (true) {
        const { done, value } = await reader.read();
        if (done) break;

        const chunk = decoder.decode(value, { stream: true });
        const lines = chunk.split('\n');

        for (const line of lines) {
          if (line.startsWith('data:')) {
            try {
              const data = JSON.parse(line.slice(5).trim());
              if (data.type === 'system') {
                addStartupLogLine(data.message, 'info');
              }
            } catch (e) {
              // 忽略解析错误
            }
          }
        }
      }

    } catch (error) {
      addStartupLogLine(`❌ 启动验证失败: ${error.message}`, 'error');
    } finally {
      btn.disabled = false;
      btn.textContent = '开始启动';
    }
  }

  /** 添加启动日志行 */
  function addStartupLogLine(message, type = 'info') {
    const logContent = document.querySelector('.log-content');
    const line = document.createElement('div');
    line.className = `log-line ${type}`;
    line.textContent = `${new Date().toLocaleTimeString()} - ${message}`;
    logContent.appendChild(line);
    logContent.scrollTop = logContent.scrollHeight;
  }

  /** 复制启动日志 */
  async function copyStartupLog() {
    const logContent = document.querySelector('.log-content');
    const logText = logContent.innerText;

    if (!logText || logText.trim().length === 0) {
      alert('没有可复制的日志');
      return;
    }

    try {
      await navigator.clipboard.writeText(logText);

      // 显示复制成功提示
      const btn = event.target;
      const originalContent = btn.innerHTML;
      btn.innerHTML = '✅ 已复制';
      btn.style.background = 'var(--accent)';
      btn.style.color = '#000';

      setTimeout(() => {
        btn.innerHTML = originalContent;
        btn.style.background = '';
        btn.style.color = '';
      }, 2000);

      // 询问用户是否要立即分析日志
      if (confirm('日志已复制到剪贴板！\n\n是否要立即分析此启动日志？\n（可以自动粘贴日志内容进行分析）')) {
        if (state.currentSession) {
          openModal('modalStartupLogAnalysis');
        }
      }
    } catch (err) {
      // 降级方案
      const textArea = document.createElement('textarea');
      textArea.value = logText;
      document.body.appendChild(textArea);
      textArea.select();
      document.execCommand('copy');
      document.body.removeChild(textArea);

      const btn = event.target;
      const originalContent = btn.innerHTML;
      btn.innerHTML = '✅ 已复制';
      btn.style.background = 'var(--accent)';
      btn.style.color = '#000';

      setTimeout(() => {
        btn.innerHTML = originalContent;
        btn.style.background = '';
        btn.style.color = '';
      }, 2000);
    }
  }

  /** 启动日志分析 */
  function analyzeStartupLog() {
    if (!state.currentSession) {
      alert('请先选择会话');
      return;
    }

    const logContent = document.getElementById('startupLogAnalysisContent');
    if (!logContent.value.trim()) {
      alert('请先复制启动日志内容');
      return;
    }

    // 关闭分析对话框
    closeModal('modalStartupLogAnalysis');

    // 在聊天中发送分析请求
    const analyzeMessage = `请分析以下启动日志，找出问题和解决方案：

项目信息：
- 数据库类型: ${state.currentSession.dbType || '未配置'}
- 项目路径: ${state.currentSession.projectPath || '未配置'}

启动日志内容：
\`\`\`
${logContent.value}
\`\`\`

请分析可能的问题并提供具体的修复建议。`;

    appendMessage('system', '🔍 正在分析启动日志...');
    streamRequest(`${API}/sessions/${state.currentSession.id}/chat`, { message: analyzeMessage });
  }
});

// ==================== 系统状态 ====================
async function checkStatus() {
  try {
    const s = await fetch(`${API}/status`).then(r => r.json());
    if (!s.claudeCliAvailable) {
      showBanner('⚠️ 未检测到 claude CLI，请安装 Claude Code：npm install -g @anthropic-ai/claude-code', 'warn');
    }
  } catch (e) {
    showBanner('⚠️ 无法连接后端服务', 'error');
  }
}

function showBanner(msg, type) {
  const bar = document.createElement('div');
  const color = type === 'error' ? 'rgba(248,113,113,' : 'rgba(251,191,36,';
  bar.style.cssText = `position:fixed;top:0;left:0;right:0;z-index:999;padding:8px 16px;
    font-size:12px;font-family:var(--font-mono);text-align:center;cursor:pointer;
    background:${color}0.15);border-bottom:1px solid ${color}0.4);
    color:${type === 'error' ? 'var(--red)' : 'var(--yellow)'};`;
  bar.textContent = msg + '  ✕';
  bar.onclick = () => bar.remove();
  document.body.prepend(bar);
}

// ==================== 进程状态轮询 ====================
function startProcessStatusPolling() {
  stopProcessStatusPolling();
  updateProcessStatus();
  state.statusTimer = setInterval(updateProcessStatus, 3000);
}

function stopProcessStatusPolling() {
  if (state.statusTimer) { clearInterval(state.statusTimer); state.statusTimer = null; }
}

async function updateProcessStatus() {
  if (!state.currentSession) return;
  try {
    const s = await fetch(`${API}/sessions/${state.currentSession.id}/process-status`).then(r => r.json());
    const dot = document.getElementById('procDot');
    const label = document.getElementById('procLabel');
    if (!dot) return;

    if (s.processing) {
      dot.className = 'proc-dot busy';
      label.textContent = '处理中...';
    } else if (s.alive) {
      dot.className = 'proc-dot alive';
      label.textContent = '进程运行中';
    } else {
      dot.className = 'proc-dot dead';
      label.textContent = '未启动';
    }
    state.processAlive = s.alive;
  } catch (e) { /* 忽略 */ }
}

// ==================== 会话管理 ====================
async function loadSessions() {
  state.sessions = await fetch(`${API}/sessions`).then(r => r.json());
  renderSessions();
}

function renderSessions() {
  const list = document.getElementById('sessionList');
  if (!state.sessions.length) {
    list.innerHTML = '<div class="empty-hint">暂无会话，点击 + 新建</div>';
    return;
  }
  list.innerHTML = state.sessions.map(s => {
    const p = PHASE_LABELS[s.status] || { icon: '💬', label: s.status || '未知' };
    const phaseText = s.status !== 'analysis' ? ` · ${p.label}` : '';
    return `
    <div class="session-item ${state.currentSession?.id === s.id ? 'active' : ''}"
         onclick="selectSession('${s.id}')">
      <div class="session-icon">${p.icon}</div>
      <div class="session-info">
        <div class="session-name">${escHtml(s.name)}</div>
        <div class="session-meta">${s.dbType || '未配置'}${phaseText} · ${formatDate(s.updatedAt)}</div>
      </div>
      <button class="session-del" onclick="deleteSession(event,'${s.id}')" title="删除">✕</button>
    </div>`;
  }).join('');
}

document.getElementById('btnNewSession').onclick = () => {
  document.getElementById('newSessionName').value = '';
  openModal('modalNewSession');
  setTimeout(() => document.getElementById('newSessionName').focus(), 100);
};

async function createSession() {
  const name = document.getElementById('newSessionName').value.trim();
  if (!name) { alert('请输入会话名称'); return; }
  const session = await fetch(`${API}/sessions`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify({ name })
  }).then(r => r.json());
  closeModal('modalNewSession');
  state.sessions.unshift(session);
  renderSessions();
  selectSession(session.id);
}

async function selectSession(id) {
  stopProcessStatusPolling();
  const session = await fetch(`${API}/sessions/${id}`).then(r => r.json());
  state.currentSession = session;
  document.getElementById('currentSessionName').textContent = session.name;
  document.getElementById('chatToolbar').style.display = 'flex';
  document.getElementById('chatInputArea').style.display = 'block';
  // 填充配置表单
  document.getElementById('cfgDbType').value = session.dbType || '';
  document.getElementById('cfgDbHost').value = session.dbHost || '';
  document.getElementById('cfgDbPort').value = session.dbPort || '';
  document.getElementById('cfgDbName').value = session.dbName || '';
  document.getElementById('cfgProjectPath').value = session.projectPath || '';
  // 加载历史数据
  state.messages = await fetch(`${API}/sessions/${id}/messages`).then(r => r.json());
  state.diffs = await fetch(`${API}/sessions/${id}/diffs`).then(r => r.json());
  renderMessages();
  renderDiffs();
  renderSessions();
  updatePhaseUI(session);
  if (session.status !== 'terminated' && session.status !== 'completed') {
    startProcessStatusPolling();
  }
}

async function deleteSession(e, id) {
  e.stopPropagation();
  if (!confirm('确认删除该会话及所有记录？')) return;
  await fetch(`${API}/sessions/${id}`, { method: 'DELETE' });
  state.sessions = state.sessions.filter(s => s.id !== id);
  if (state.currentSession?.id === id) {
    stopProcessStatusPolling();
    state.currentSession = null;
    state.messages = []; state.diffs = [];
    document.getElementById('currentSessionName').textContent = '请选择或新建会话';
    document.getElementById('chatToolbar').style.display = 'none';
    document.getElementById('chatInputArea').style.display = 'none';
    renderMessages(); renderDiffs();
  }
  renderSessions();
}

async function saveConfig() {
  const session = state.currentSession;
  if (!session) { alert('请先选择会话'); return; }
  const body = {
    dbType: document.getElementById('cfgDbType').value,
    dbHost: document.getElementById('cfgDbHost').value,
    dbPort: document.getElementById('cfgDbPort').value,
    dbName: document.getElementById('cfgDbName').value,
    projectPath: document.getElementById('cfgProjectPath').value,
  };
  const updated = await fetch(`${API}/sessions/${session.id}`, {
    method: 'PUT',
    headers: {'Content-Type': 'application/json'},
    body: JSON.stringify(body)
  }).then(r => r.json());
  state.currentSession = updated;
  state.sessions = state.sessions.map(s => s.id === updated.id ? updated : s);
  renderSessions();
  updateProcessStatus();
  const hint = document.getElementById('saveHint');
  hint.textContent = '✓ 已保存，claude 进程已重置';
  setTimeout(() => hint.textContent = '', 3000);
}

// ==================== 两阶段工作流控制 ====================

/** 开始分析 */
async function startAnalysis() {
  if (!state.currentSession) { alert('请先选择会话'); return; }
  if (!state.currentSession.projectPath) {
    alert('请先在配置中设置项目路径');
    switchTab('config');
    return;
  }
  if (!state.currentSession.dbType) {
    alert('请先在配置中选择目标数据库类型');
    switchTab('config');
    return;
  }
  if (state.isStreaming) { alert('当前有消息正在处理中，请等待完成'); return; }

  appendMessage('system', '🚀 开始分析项目，AI 将扫描项目文件并生成适配方案...');
  await streamRequest(`${API}/sessions/${state.currentSession.id}/start-analysis`, {});
}

/** 确认方案，进入执行阶段 */
async function confirmPlan() {
  if (!state.currentSession) return;

  const pendingDiffs = state.diffs.filter(d => !d.applied);
  if (pendingDiffs.length === 0) {
    alert('没有待确认的修改方案');
    return;
  }

  const confirmed = confirm(
    `📋 方案确认\n\n` +
    `共 ${pendingDiffs.length} 项修改待执行。\n\n` +
    `确认后将进入执行阶段，您可以：\n` +
    `- 逐条应用/拒绝修改\n` +
    `- 一键执行所有修改\n\n` +
    `确认进入执行阶段？`
  );
  if (!confirmed) return;

  const r = await fetch(`${API}/sessions/${state.currentSession.id}/confirm-plan`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'}
  }).then(r => r.json());

  if (r.error) { alert(`操作失败: ${r.error}`); return; }

  // 刷新会话状态
  state.currentSession = await fetch(`${API}/sessions/${state.currentSession.id}`).then(r => r.json());
  updatePhaseUI(state.currentSession);
  appendMessage('system', `✅ 方案已确认，进入执行阶段。共 ${r.pendingCount} 项修改待执行。`);
  renderSessions();
}

/** 批量应用所有修改 */
async function applyAllDiffs() {
  if (!state.currentSession) return;

  const pendingDiffs = state.diffs.filter(d => !d.applied);
  if (pendingDiffs.length === 0) {
    alert('没有待执行的修改');
    return;
  }

  const confirmed = confirm(
    `⚡ 执行修改\n\n` +
    `将应用全部 ${pendingDiffs.length} 项修改到项目文件。\n` +
    `原文件将自动备份。\n\n` +
    `确认执行？`
  );
  if (!confirmed) return;

  const btn = document.getElementById('btnApplyAll');
  btn.disabled = true;
  btn.textContent = '⏳ 执行中...';

  try {
    const r = await fetch(`${API}/sessions/${state.currentSession.id}/apply-all-diffs`, {
      method: 'POST',
      headers: {'Content-Type': 'application/json'}
    }).then(r => r.json());

    if (r.ok) {
      const msg = r.failed > 0
        ? `⚠️ ${r.applied} 项修改已应用，${r.failed} 项失败`
        : `✅ 全部 ${r.applied} 项修改已成功应用！`;
      appendMessage('system', msg);
      alert(msg);
    } else {
      alert(`执行失败: ${r.error}`);
    }

    // 刷新状态
    state.currentSession = await fetch(`${API}/sessions/${state.currentSession.id}`).then(r => r.json());
    state.diffs = await fetch(`${API}/sessions/${state.currentSession.id}/diffs`).then(r => r.json());
    renderDiffs();
    updatePhaseUI(state.currentSession);
    renderSessions();
  } catch (e) {
    alert(`执行请求失败: ${e.message}`);
  } finally {
    btn.disabled = false;
    btn.innerHTML = '<span>⚡</span> 执行修改';
  }
}

/** 进入评审阶段 */
async function enterReview() {
  if (!state.currentSession) return;
  const r = await fetch(`${API}/sessions/${state.currentSession.id}/enter-review`, {
    method: 'POST',
    headers: {'Content-Type': 'application/json'}
  }).then(r => r.json());
  if (r.error) { alert(r.error); return; }
  state.currentSession = await fetch(`${API}/sessions/${state.currentSession.id}`).then(r => r.json());
  updatePhaseUI(state.currentSession);
  renderSessions();
}

// ==================== 阶段 UI 控制 ====================

function updatePhaseUI(session) {
  const phase = session?.status || 'analysis';
  const p = PHASE_LABELS[phase] || PHASE_LABELS.analysis;

  // 阶段徽标
  const badge = document.getElementById('phaseBadge');
  badge.style.display = 'inline-block';
  badge.textContent = `${p.icon} ${p.label}`;
  badge.style.background = p.color + '20';
  badge.style.color = p.color;
  badge.style.border = `1px solid ${p.color}40`;

  const isTerminated = phase === 'terminated';
  const isCompleted = phase === 'completed';

  // 按钮显隐
  const btnStartAnalysis = document.getElementById('btnStartAnalysis');
  const btnConfirmPlan = document.getElementById('btnConfirmPlan');
  const btnApplyAll = document.getElementById('btnApplyAll');
  const btnValidateStartup = document.getElementById('btnValidateStartup');
  const btnTerminate = document.getElementById('btnTerminate');
  const btnReset = document.getElementById('btnReset');
  const btnSend = document.getElementById('btnSend');
  const btnScan = document.getElementById('btnScan');
  const chatInputArea = document.getElementById('chatInputArea');

  // 默认隐藏
  btnConfirmPlan.style.display = 'none';
  btnApplyAll.style.display = 'none';
  btnValidateStartup.style.display = 'none';

  if (isTerminated) {
    btnStartAnalysis.style.display = 'none';
    btnTerminate.disabled = true; btnTerminate.style.opacity = '0.4';
    btnReset.disabled = true; btnReset.style.opacity = '0.4';
    btnSend.disabled = true;
    btnScan.disabled = true;
    chatInputArea.style.display = 'none';
    showTerminatedOverlay();
    return;
  }

  if (isCompleted) {
    btnStartAnalysis.style.display = 'none';
    btnTerminate.disabled = true; btnTerminate.style.opacity = '0.4';
    btnReset.disabled = true; btnReset.style.opacity = '0.4';
    btnSend.disabled = true;
    btnScan.disabled = true;
    chatInputArea.style.display = 'none';
    showCompletedOverlay();
    return;
  }

  // 移除遮罩
  removeOverlay();

  // 恢复通用按钮
  btnTerminate.disabled = false; btnTerminate.style.opacity = '1';
  btnReset.disabled = false; btnReset.style.opacity = '1';
  btnScan.disabled = false;
  chatInputArea.style.display = 'block';

  switch (phase) {
    case 'analysis':
      btnStartAnalysis.style.display = 'inline-flex';
      btnConfirmPlan.style.display = 'none';
      btnApplyAll.style.display = 'none';
      btnValidateStartup.style.display = 'inline-flex';
      btnSend.disabled = false;
      break;

    case 'review':
      btnStartAnalysis.style.display = 'none';
      btnConfirmPlan.style.display = 'inline-flex';
      btnApplyAll.style.display = 'inline-flex';
      btnValidateStartup.style.display = 'inline-flex';
      btnSend.disabled = false;
      break;

    case 'execution':
      btnStartAnalysis.style.display = 'none';
      btnConfirmPlan.style.display = 'none';
      btnApplyAll.style.display = 'inline-flex';
      btnValidateStartup.style.display = 'inline-flex';
      btnSend.disabled = false;
      break;
  }
}

function showTerminatedOverlay() {
  removeOverlay();
  const chatContainer = document.querySelector('.chat-container');
  chatContainer.style.position = 'relative';
  const overlay = document.createElement('div');
  overlay.className = 'session-terminated-overlay';
  overlay.id = 'phaseOverlay';
  overlay.innerHTML = `
    <div class="terminated-icon">⛔</div>
    <div class="terminated-text">会话已终止</div>
    <div class="terminated-sub">本轮所有文件修改已回滚，对话记录仍可查看</div>`;
  chatContainer.appendChild(overlay);
}

function showCompletedOverlay() {
  removeOverlay();
  const chatContainer = document.querySelector('.chat-container');
  chatContainer.style.position = 'relative';
  const overlay = document.createElement('div');
  overlay.className = 'session-terminated-overlay';
  overlay.id = 'phaseOverlay';
  overlay.innerHTML = `
    <div class="terminated-icon">✅</div>
    <div class="terminated-text" style="color:var(--accent)">适配完成</div>
    <div class="terminated-sub">所有修改已成功应用，对话记录和修改记录仍可查看</div>`;
  chatContainer.appendChild(overlay);
}

function removeOverlay() {
  document.getElementById('phaseOverlay')?.remove();
}

// ==================== 重置 claude 会话 ====================
async function resetSession() {
  if (!state.currentSession) { alert('请先选择会话'); return; }
  if (['terminated','completed'].includes(state.currentSession.status)) { alert('该会话已结束，无法操作'); return; }
  if (state.isStreaming) { alert('当前有消息正在处理中，请等待完成'); return; }
  if (!confirm('重置会话将清空所有对话记录和修改记录，确认？')) return;

  try {
    const r = await fetch(`${API}/sessions/${state.currentSession.id}/reset`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    }).then(r => r.json());

    if (r.error) { alert(`重置失败: ${r.error}`); return; }

    // 清空本地状态
    state.messages = [];
    state.diffs = [];
    renderMessages();
    renderDiffs();
    appendMessage('system', '🔄 会话已重置，上下文已清空。请重新开始对话。');
  } catch (e) {
    alert(`重置失败: ${e.message}`);
  }
}

// ==================== 终止会话（回滚所有修改） ====================
async function terminateSession() {
  if (!state.currentSession) { alert('请先选择会话'); return; }
  if (['terminated','completed'].includes(state.currentSession.status)) { alert('该会话已结束'); return; }
  if (state.isStreaming) { alert('当前有消息正在处理中，请等待完成后再终止'); return; }

  const confirmed = confirm(
    '⚠️ 终止会话将：\n\n' +
    '1. 关闭 AI 进程，停止所有操作\n' +
    '2. 回滚本轮会话中所有已应用的文件修改\n' +
    '3. 会话变为"已终止"状态，无法继续对话\n\n' +
    '确认终止？此操作不可撤销！'
  );
  if (!confirmed) return;

  const btn = document.getElementById('btnTerminate');
  btn.disabled = true;
  btn.textContent = '⏳ 终止中...';

  try {
    const r = await fetch(`${API}/sessions/${state.currentSession.id}/terminate`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' }
    }).then(r => r.json());

    if (r.error) { alert(`终止失败: ${r.error}`); btn.disabled = false; btn.textContent = '⛔ 终止会话'; return; }

    state.currentSession.status = 'terminated';
    updatePhaseUI(state.currentSession);

    state.messages = await fetch(`${API}/sessions/${state.currentSession.id}/messages`).then(r => r.json());
    state.diffs = await fetch(`${API}/sessions/${state.currentSession.id}/diffs`).then(r => r.json());
    renderMessages();
    renderDiffs();
    renderSessions();

    appendMessage('system', `⛔ 会话已终止。${r.message || '所有修改已回滚。'}`);
  } catch (e) {
    alert(`终止请求失败: ${e.message}`);
    btn.disabled = false;
    btn.textContent = '⛔ 终止会话';
  }
}

// ==================== Tab 切换 ====================
function switchTab(tab) {
  document.querySelectorAll('.tab-content').forEach(t => t.classList.remove('active'));
  document.querySelectorAll('.btn-tab').forEach(t => t.classList.remove('active'));
  document.getElementById(`tabContent${cap(tab)}`).classList.add('active');
  document.getElementById(`tab${cap(tab)}`).classList.add('active');
  if (tab === 'diff' && state.currentSession) refreshDiffs();
  if (tab === 'config') setTimeout(refreshCliStatus, 100);
}
function cap(s) { return s.charAt(0).toUpperCase() + s.slice(1); }

document.getElementById('btnToggleSidebar').onclick = () => {
  state.sidebarOpen = !state.sidebarOpen;
  document.getElementById('sidebar').classList.toggle('collapsed', !state.sidebarOpen);
};

// ==================== 消息渲染 ====================
function renderMessages() {
  const container = document.getElementById('chatMessages');
  if (!state.currentSession || !state.messages.length) {
    container.innerHTML = `<div class="welcome-screen">
      <div class="welcome-icon">⬡</div>
      <h2>国产数据库适配助手</h2>
      <p>配置好数据库类型和项目路径后，点击「开始分析」启动适配流程</p>
      <div class="welcome-tips">
        <div class="tip">🔍 分析阶段：AI 扫描项目，输出适配方案</div>
        <div class="tip">📋 评审阶段：查看方案，逐条确认或调整</div>
        <div class="tip">⚡ 执行阶段：确认后应用修改到项目文件</div>
        <div class="tip">⛔ 随时终止：回滚所有已应用的修改</div>
      </div>
    </div>`;
    return;
  }
  container.innerHTML = state.messages.map(renderMessage).join('');
  scrollToBottom();
}

function renderMessage(m) {
  const avatar = { user: '👤', assistant: '⬡', system: '⚙' }[m.role] || '?';
  const content = m.role === 'assistant'
    ? renderMarkdown(m.content)
    : escHtml(m.content).replace(/\n/g, '<br>');
  return `<div class="msg ${m.role}">
    <div class="msg-avatar">${avatar}</div>
    <div class="msg-bubble">${content}</div>
  </div>`;
}

function appendMessage(role, content) {
  const m = { id: Date.now(), role, content, createdAt: new Date().toISOString() };
  state.messages.push(m);
  const container = document.getElementById('chatMessages');
  const div = document.createElement('div');
  div.className = `msg ${role}`;
  div.id = `msg-${m.id}`;
  const avatar = { user: '👤', assistant: '⬡', system: '⚙' }[role] || '?';
  div.innerHTML = `<div class="msg-avatar">${avatar}</div>
    <div class="msg-bubble">${role === 'assistant' ? '' : escHtml(content)}</div>`;
  container.appendChild(div);
  scrollToBottom();
  return div;
}

// ==================== 输入处理 ====================
function handleInputKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); sendMessage(); }
}
function autoResize(el) {
  el.style.height = 'auto';
  el.style.height = Math.min(el.scrollHeight, 160) + 'px';
}

async function sendMessage() {
  if (!state.currentSession) { alert('请先选择会话'); return; }
  if (['terminated','completed'].includes(state.currentSession.status)) {
    alert('该会话已结束，无法发送消息'); return;
  }
  if (state.isStreaming) return;
  const input = document.getElementById('chatInput');
  const text = input.value.trim();
  if (!text) return;
  input.value = '';
  input.style.height = 'auto';

  if (!state.currentSession.projectPath) {
    appendMessage('system',
      '⚠️ 未配置项目路径，claude 将在服务器当前目录运行。建议先在「配置」中设置项目路径。');
  }

  appendMessage('user', text);
  await streamRequest(`${API}/sessions/${state.currentSession.id}/chat`, { message: text });
}

// ==================== 流式请求 ====================
async function streamRequest(url, body) {
  state.isStreaming = true;
  setButtonsDisabled(true);

  const aiDiv = appendMessage('assistant', '');
  const bubble = aiDiv.querySelector('.msg-bubble');
  bubble.classList.add('streaming-cursor');
  let fullText = '';

  // 添加实时处理提示
  const progressDiv = document.createElement('div');
  progressDiv.className = 'processing-progress';
  progressDiv.style.cssText = `
    margin-top: 8px;
    font-size: 12px;
    color: var(--text-muted);
    font-family: var(--font-mono);
    padding: 4px 0;
  `;
  progressDiv.innerHTML = '🔄 正在处理...';
  bubble.appendChild(progressDiv);

  const dot = document.getElementById('procDot');
  const label = document.getElementById('procLabel');
  if (dot) { dot.className = 'proc-dot busy'; label.textContent = '处理中...'; }

  try {
    const res = await fetch(url, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body)
    });

    if (!res.ok) {
      const err = await res.json().catch(() => ({ error: '请求失败' }));
      bubble.classList.remove('streaming-cursor');
      progressDiv.remove();
      bubble.innerHTML = `❌ ${escHtml(err.error || '请求失败')}`;
      return;
    }

    const reader = res.body.getReader();
    const decoder = new TextDecoder();
    let buf = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buf += decoder.decode(value, { stream: true });
      const lines = buf.split('\n');
      buf = lines.pop();

      for (const line of lines) {
        if (!line.startsWith('data:')) continue;
        try {
          const data = JSON.parse(line.slice(5).trim());

          if (data.type === 'chunk') {
            fullText += data.text;
            bubble.innerHTML = renderMarkdown(fullText);
            bubble.classList.add('streaming-cursor');
            progressDiv.innerHTML = `🔄 已处理 ${fullText.length} 字符...`;
            scrollToBottom();

          } else if (data.type === 'done') {
            bubble.classList.remove('streaming-cursor');
            progressDiv.remove();
            bubble.innerHTML = renderMarkdown(fullText);

            if (data.modifications?.length > 0) {
              const notice = document.createElement('div');
              notice.style.cssText =
                'margin-top:10px;padding:8px 12px;background:rgba(74,222,128,0.1);' +
                'border:1px solid rgba(74,222,128,0.3);border-radius:6px;font-size:12px;' +
                'color:#4ade80;cursor:pointer;';
              const phase = state.currentSession?.status;
              if (phase === 'analysis') {
                notice.textContent =
                  `📋 生成了 ${data.modifications.length} 处修改建议 → 点击查看「修改记录」，确认后执行`;
              } else {
                notice.textContent =
                  `📝 已生成 ${data.modifications.length} 处修改 → 点击查看「修改记录」`;
              }
              notice.onclick = () => switchTab('diff');
              bubble.appendChild(notice);

              // 分析阶段自动进入评审
              if (phase === 'analysis' && data.modifications.length > 0) {
                setTimeout(() => {
                  enterReview();
                  // 添加提示
                  const enterNotice = document.createElement('div');
                  enterNotice.style.cssText = 'margin-top:8px;font-size:12px;color:var(--yellow)';
                  enterNotice.textContent = '✅ 已自动进入方案评审阶段';
                  bubble.appendChild(enterNotice);
                }, 500);
              }
            }
            await refreshDiffs();

          } else if (data.type === 'error') {
            bubble.classList.remove('streaming-cursor');
            progressDiv.remove();
            bubble.innerHTML += `<br><br>❌ <span style="color:var(--red)">${escHtml(data.message)}</span>`;

          } else if (data.type === 'system') {
            appendMessage('system', data.text || '');
            // 更新进度提示
            if (data.text?.includes('正在扫描') || data.text?.includes('分析中')) {
              progressDiv.innerHTML = `🔄 ${data.text}`;
            }
          }
        } catch (e) {
          // 忽略解析错误，继续处理
        }
      }
    }
  } catch (e) {
    bubble.classList.remove('streaming-cursor');
    progressDiv.remove();
    bubble.innerHTML = `❌ 网络错误: ${escHtml(e.message)}`;
  } finally {
    state.isStreaming = false;
    setButtonsDisabled(false);
    scrollToBottom();
    updateProcessStatus();
  }
}

function setButtonsDisabled(d) {
  ['btnSend', 'btnScan', 'btnReset', 'btnStartAnalysis', 'btnConfirmPlan', 'btnApplyAll', 'btnValidateStartup'].forEach(id => {
    const el = document.getElementById(id);
    if (el) el.disabled = d;
  });
}

// ==================== 扫描项目 ====================
async function scanProject() {
  if (!state.currentSession) { alert('请先选择会话'); return; }
  document.getElementById('btnScan').disabled = true;
  try {
    const result = await fetch(`${API}/sessions/${state.currentSession.id}/scan`, {
      method: 'POST', headers: {'Content-Type': 'application/json'}
    }).then(r => r.json());

    if (result.error) {
      appendMessage('system', `❌ 扫描失败: ${result.error}`);
    } else {
      const f = result.files;
      const lines = ['📁 项目扫描完成'];
      if (f.pom?.length)    lines.push('📦 POM:\n' + f.pom.slice(0,5).join('\n'));
      if (f.config?.length) lines.push('⚙️ 配置:\n' + f.config.slice(0,5).join('\n'));
      if (f.mapper?.length) lines.push('🗄️ Mapper:\n' + f.mapper.slice(0,5).join('\n'));
      if (f.java?.length)   lines.push('☕ Java配置:\n' + f.java.slice(0,5).join('\n'));

      const scanMsg = lines.join('\n\n');
      appendMessage('system', scanMsg);

      const claudePrompt = `项目扫描结果：\n${scanMsg}\n\n请记住这些文件，后续适配时直接读取它们。`;
      await streamRequest(`${API}/sessions/${state.currentSession.id}/chat`, { message: claudePrompt });
      return;
    }
    state.messages = await fetch(`${API}/sessions/${state.currentSession.id}/messages`).then(r=>r.json());
    renderMessages();
  } catch (e) {
    appendMessage('system', `❌ 扫描错误: ${e.message}`);
  }
  document.getElementById('btnScan').disabled = false;
}

// ==================== Diff ====================
async function refreshDiffs() {
  if (!state.currentSession) return;
  state.diffs = await fetch(`${API}/sessions/${state.currentSession.id}/diffs`).then(r => r.json());
  renderDiffs();
}

function renderDiffs() {
  const container = document.getElementById('diffContainer');
  if (!state.diffs.length) {
    container.innerHTML = '<div class="empty-hint" style="padding:40px;text-align:center">' +
      'AI 回复中包含修改建议时，会自动在此记录。</div>';
    return;
  }
  const phase = state.currentSession?.status || 'analysis';
  const canReject = phase === 'analysis' || phase === 'review';
  const canApply = phase === 'review' || phase === 'execution';

  container.innerHTML = state.diffs.map(d => {
    // 计算显示用的相对路径
    let relativePath = d.filePath;
    if (state.currentSession?.projectPath && d.filePath.startsWith(state.currentSession.projectPath)) {
      relativePath = d.filePath.substring(state.currentSession.projectPath.length);
    }

    const statusBadge = d.applied
      ? (d.autoApplied ? '🤖 AI 已修改' : '✓ 已应用')
      : (phase === 'analysis' ? '📋 待确认' : '待执行');
    const statusClass = d.applied ? 'applied' : 'pending';

    // 文件图标
    const fileIcon = getFileIcon(d.filePath);

    return `
    <div class="diff-card ${d.applied ? 'applied' : ''}" id="diff-${d.id}">
      <div class="diff-header" onclick="toggleDiff('${d.id}')">
        <div style="flex:1;overflow:hidden">
          <div class="diff-file">
            <span class="file-icon">${fileIcon}</span>
            <span>${escHtml(relativePath)}</span>
          </div>
          <div class="diff-desc" style="margin-top:3px">${escHtml(d.description||'')}</div>
          ${d.fileExtension ? `<div class="file-meta">类型: ${d.fileExtension} | 大小: ${formatFileSize(d.contentSize)}</div>` : ''}
        </div>
        <div class="diff-status ${statusClass}">${statusBadge}</div>
        <div style="color:var(--text-muted);font-size:12px;margin-left:8px;font-family:var(--font-mono)">
          ${formatDate(d.createdAt)}</div>
      </div>
      <div class="diff-preview" id="preview-${d.id}">
        <div class="diff-split">
          <div class="diff-side original">
            <div class="diff-side-label">▼ 修改前</div>
            <pre style="white-space:pre-wrap;word-break:break-all">${escHtml(d.originalContent||'')}</pre>
          </div>
          <div class="diff-side modified">
            <div class="diff-side-label">▲ 修改后</div>
            <pre style="white-space:pre-wrap;word-break:break-all">${escHtml(d.modifiedContent||'')}</pre>
          </div>
        </div>
        <div class="diff-actions">
          ${!d.applied && canReject ? `<button class="btn-danger" onclick="rejectDiff('${d.id}')">拒绝</button>` : ''}
          ${!d.applied && canApply ? `<button class="btn-primary" onclick="applyDiffById('${d.id}')">应用此修改</button>` : ''}
          ${d.applied ? `
            <span style="color:var(--accent);font-size:12px;font-family:var(--font-mono)">
              ${d.autoApplied ? '🤖 AI 已直接修改文件' : '✓ 已应用'}
              ${d.backupPath ? `<br>备份: ${escHtml(d.backupPath)}` : ''}
            </span>
            ${d.backupPath ? `<button class="btn-danger" style="margin-left:10px" onclick="rollbackDiff('${d.id}')">⏪ 回滚</button>` : ''}
          ` : ''}
        </div>
      </div>
    </div>`;
  }).join('');
}

function toggleDiff(id) { document.getElementById(`preview-${id}`)?.classList.toggle('open'); }

async function applyDiffById(diffId) {
  if (!confirm('确认将此修改写入文件？原文件将自动备份。')) return;
  const r = await fetch(`${API}/diffs/${diffId}/apply`, {
    method: 'POST', headers: {'Content-Type': 'application/json'}
  }).then(r => r.json());
  if (r.error) alert(`应用失败: ${r.error}`);
  else { alert(`✅ 修改已应用！备份: ${r.backupPath}`); await refreshDiffs(); }
}

async function rejectDiff(diffId) {
  if (!state.currentSession) return;
  if (!confirm('确认拒绝此修改建议？')) return;
  const r = await fetch(`${API}/sessions/${state.currentSession.id}/diffs/${diffId}/reject`, {
    method: 'DELETE', headers: {'Content-Type': 'application/json'}
  }).then(r => r.json());
  if (r.error) alert(r.error);
  else await refreshDiffs();
}

async function deleteDiff(diffId) {
  await fetch(`${API}/diffs/${diffId}`, { method: 'DELETE' });
  document.getElementById(`diff-${diffId}`)?.remove();
  state.diffs = state.diffs.filter(d => d.id !== diffId);
}

async function rollbackDiff(diffId) {
  if (!confirm('确认从备份恢复文件？这将撤销之前的修改。')) return;
  const r = await fetch(`${API}/diffs/${diffId}/rollback`, {
    method: 'POST', headers: {'Content-Type': 'application/json'}
  }).then(r => r.json());
  if (r.error) alert(`回滚失败: ${r.error}`);
  else { alert(`✅ 已从备份恢复文件`); await refreshDiffs(); }
}

// ==================== 配置 Tab - CLI 状态 ====================
async function refreshCliStatus() {
  const badge = document.getElementById('cliStatusBadge');
  const procs = document.getElementById('cliActiveProcs');
  if (!badge) return;
  badge.className = 'cli-badge checking'; badge.textContent = '检测中...';
  try {
    const s = await fetch('/api/status').then(r => r.json());
    badge.className = s.claudeCliAvailable ? 'cli-badge ok' : 'cli-badge error';
    badge.textContent = s.claudeCliAvailable
      ? `✓ ${s.resolvedCliPath || '可用'}` : '✗ 不可用';
    if (procs) procs.textContent = s.activeProcesses ?? 0;
  } catch (e) {
    badge.className = 'cli-badge error'; badge.textContent = '✗ 连接失败';
  }
}
setTimeout(refreshCliStatus, 800);

async function testClaudeConnection() {
  const btn = event.target;
  btn.disabled = true;
  btn.textContent = '🧪 测试中...';
  try {
    const s = await fetch('/api/status').then(r => r.json());
    if (s.claudeCliAvailable) {
      alert(`✅ Claude CLI 连接成功！\n\n路径: ${s.resolvedCliPath}\n当前进程数: ${s.activeProcesses}`);
    } else {
      alert(`❌ Claude CLI 不可用\n\n${s.message || '请检查安装'}`);
    }
  } catch (e) {
    alert(`❌ 测试失败\n\n${e.message}`);
  } finally {
    btn.disabled = false;
    btn.textContent = '🧪 测试连接';
  }
}

// ==================== 数据库参考卡片 ====================
const DB_REF = [
  { name: '达梦 DM8',     port: '5236',  url: 'jdbc:dm://host:5236/DB',          driver: 'dm.jdbc.driver.DmDriver' },
  { name: '人大金仓 KES', port: '54321', url: 'jdbc:kingbase8://host:54321/DB',   driver: 'com.kingbase8.Driver' },
  { name: '华为 GaussDB', port: '8000',  url: 'jdbc:gaussdb://host:8000/DB',      driver: 'com.huawei.gaussdb.jdbc.Driver' },
  { name: 'TiDB',         port: '4000',  url: 'jdbc:mysql://host:4000/DB',         driver: 'com.mysql.cj.jdbc.Driver' },
  { name: '神通 Oscar',   port: '2003',  url: 'jdbc:oscar://host:2003/DB',         driver: 'com.oscar.Driver' },
];
function renderDbRefCards() {
  document.getElementById('dbRefCards').innerHTML = DB_REF.map(db => `
    <div class="db-ref-card">
      <div class="db-ref-card-name">${db.name}</div>
      <div class="db-ref-card-info">端口: ${db.port}<br>${db.url}<br>${db.driver}</div>
    </div>`).join('');
}

// ==================== 工具 ====================
function openModal(id)  { document.getElementById(id).style.display = 'flex'; }
function closeModal(id) { document.getElementById(id).style.display = 'none'; }
function scrollToBottom() { const c = document.getElementById('chatMessages'); c.scrollTop = c.scrollHeight; }
function escHtml(s) {
  return !s ? '' : String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}
function formatDate(iso) {
  if (!iso) return '';
  const d = new Date(iso), now = new Date(), diff = now - d;
  if (diff < 60000) return '刚刚';
  if (diff < 3600000) return `${Math.floor(diff/60000)}分前`;
  if (diff < 86400000) return `${Math.floor(diff/3600000)}时前`;
  return `${d.getMonth()+1}/${d.getDate()}`;
}
function renderMarkdown(text) {
  if (!text) return '';
  let h = escHtml(text);
  h = h.replace(/```(\w*)\n?([\s\S]*?)```/g, (_,l,c) => `<pre><code class="lang-${l}">${c}</code></pre>`);
  h = h.replace(/`([^`]+)`/g, '<code>$1</code>');
  h = h.replace(/^### (.+)$/gm, '<h3>$1</h3>');
  h = h.replace(/^## (.+)$/gm,  '<h2>$1</h2>');
  h = h.replace(/^# (.+)$/gm,   '<h1>$1</h1>');
  h = h.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>');
  h = h.replace(/^---+$/gm, '<hr>');
  h = h.replace(/^[•\-\*] (.+)$/gm, '<li>$1</li>');
  h = h.replace(/(<li>[\s\S]*?<\/li>)+/g, m => `<ul>${m}</ul>`);
  h = h.replace(/\n\n/g, '</p><p>').replace(/\n/g, '<br>');
  return `<p>${h}</p>`;
}

// ==================== 文件相关工具函数 ====================

/** 获取文件图标 */
function getFileIcon(filePath) {
  if (!filePath) return '📄';
  const ext = filePath.split('.').pop()?.toLowerCase();

  const iconMap = {
    // Java 相关
    'java': '☕',
    'xml': '📋',
    'properties': '⚙️',
    'yml': '⚙️',
    'yaml': '⚙️',
    'json': '📊',
    'xml': '📋',

    // 数据库相关
    'sql': '🗄️',
    'ddl': '🗄️',
    'mapper': '🗄️',

    // Web 相关
    'js': '📜',
    'ts': '📜',
    'html': '🌐',
    'css': '🎨',
    'vue': '💚',
    'jsx': '⚛️',
    'tsx': '⚛️',

    // 配置文件
    'conf': '⚙️',
    'config': '⚙️',
    'ini': '⚙️',
    'toml': '📝',

    // 文档
    'md': '📖',
    'txt': '📄',
    'doc': '📄',
    'docx': '📄',
    'pdf': '📕',

    // 构建相关
    'pom': '📦',
    'gradle': '📦',
    'jar': '🧩',
    'war': '🧩',

    // 其他
    'sh': '🐚',
    'bat': '🐚',
    'py': '🐍',
    'go': '🔧',
    'rs': '🦀'
  };

  return iconMap[ext] || '📄';
}

/** 格式化文件大小 */
function formatFileSize(bytes) {
  if (!bytes || bytes === 0) return '0 B';

  const units = ['B', 'KB', 'MB', 'GB'];
  let size = bytes;
  let unitIndex = 0;

  while (size >= 1024 && unitIndex < units.length - 1) {
    size = size / 1024;
    unitIndex++;
  }

  return `${size.toFixed(1)} ${units[unitIndex]}`;
}
document.querySelectorAll('.modal-overlay').forEach(o => {
  o.addEventListener('click', e => { if (e.target === o) o.style.display = 'none'; });
});
