/**
 * 国产数据库适配助手 - 前端逻辑
 * claude 持久进程模式：每个会话对应一个 claude 进程，多轮对话保留上下文
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

// ==================== 初始化 ====================
document.addEventListener('DOMContentLoaded', async () => {
  await checkStatus();
  await loadSessions();
  renderDbRefCards();
  document.getElementById('newSessionName').addEventListener('keydown', e => {
    if (e.key === 'Enter') createSession();
  });
  // 切换 Tab 时刷新状态
  document.getElementById('tabConfig').addEventListener('click', () => {
    setTimeout(refreshCliStatus, 100);
  });
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
  updateProcessStatus(); // 立即查一次
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
  list.innerHTML = state.sessions.map(s => `
    <div class="session-item ${state.currentSession?.id === s.id ? 'active' : ''}"
         onclick="selectSession('${s.id}')">
      <div class="session-icon">💬</div>
      <div class="session-info">
        <div class="session-name">${escHtml(s.name)}</div>
        <div class="session-meta">${s.dbType || '未配置'} · ${formatDate(s.updatedAt)}</div>
      </div>
      <button class="session-del" onclick="deleteSession(event,'${s.id}')" title="删除">✕</button>
    </div>
  `).join('');
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
  startProcessStatusPolling();
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
  // PUT 接口会关闭旧 claude 进程
  updateProcessStatus();
  const hint = document.getElementById('saveHint');
  hint.textContent = '✓ 已保存，claude 进程已重置';
  setTimeout(() => hint.textContent = '', 3000);
}

// ==================== 重置 claude 会话 ====================
async function resetSession() {
  if (!state.currentSession) { alert('请先选择会话'); return; }
  if (state.isStreaming) { alert('当前有消息正在处理中，请等待完成'); return; }
  if (!confirm('重置 claude 进程将清空 AI 的上下文记忆（对话记录保留），确认？')) return;

  const firstMsg = '你好，我们重新开始。' +
    (state.currentSession.dbType ? `目标数据库是${state.currentSession.dbType}，` : '') +
    (state.currentSession.projectPath ? `项目路径是 ${state.currentSession.projectPath}。` : '') +
    '请准备开始适配工作。';

  await streamRequest(`${API}/sessions/${state.currentSession.id}/reset-chat`, { message: firstMsg });
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
      <p>配置好数据库类型和项目路径后，直接对话即可开始适配。<br>
         claude 会在项目目录下运行，可直接读写项目文件。</p>
      <div class="welcome-tips">
        <div class="tip">🤖 持久进程多轮对话</div>
        <div class="tip">📂 直接操作项目文件</div>
        <div class="tip">🌐 使用全局 Skill 规则</div>
        <div class="tip">📝 修改记录可回溯</div>
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
  if (state.isStreaming) return;
  const input = document.getElementById('chatInput');
  const text = input.value.trim();
  if (!text) return;
  input.value = '';
  input.style.height = 'auto';

  // 首次发消息，若未配置项目路径则提示
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

  // 更新进程状态为 busy
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
            scrollToBottom();

          } else if (data.type === 'done') {
            bubble.classList.remove('streaming-cursor');
            bubble.innerHTML = renderMarkdown(fullText);
            if (data.modifications?.length > 0) {
              const notice = document.createElement('div');
              notice.style.cssText =
                'margin-top:10px;padding:8px 12px;background:rgba(74,222,128,0.1);' +
                'border:1px solid rgba(74,222,128,0.3);border-radius:6px;font-size:12px;' +
                'color:#4ade80;cursor:pointer;';
              notice.textContent =
                `📝 已生成 ${data.modifications.length} 处修改建议 → 点击查看「修改记录」`;
              notice.onclick = () => switchTab('diff');
              bubble.appendChild(notice);
            }
            await refreshDiffs();

          } else if (data.type === 'error') {
            bubble.classList.remove('streaming-cursor');
            bubble.innerHTML += `<br><br>❌ <span style="color:var(--red)">${escHtml(data.message)}</span>`;

          } else if (data.type === 'system') {
            appendMessage('system', data.text || '');
          }
        } catch (e) { /* 忽略 */ }
      }
    }
  } catch (e) {
    bubble.classList.remove('streaming-cursor');
    bubble.innerHTML = `❌ 网络错误: ${escHtml(e.message)}`;
  } finally {
    state.isStreaming = false;
    setButtonsDisabled(false);
    scrollToBottom();
    // 恢复进程状态轮询
    updateProcessStatus();
  }
}

function setButtonsDisabled(d) {
  ['btnSend', 'btnScan', 'btnReset'].forEach(id => {
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

      // 同步将扫描摘要发给 claude，让 AI 知晓项目结构
      const claudePrompt = `项目扫描结果：\n${scanMsg}\n\n请记住这些文件，后续适配时直接读取它们。`;
      await streamRequest(`${API}/sessions/${state.currentSession.id}/chat`, { message: claudePrompt });
      return; // streamRequest 内部已重置按钮
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
  container.innerHTML = state.diffs.map(d => {
    const statusBadge = d.applied
      ? (d.autoApplied ? '🤖 AI 已修改' : '✓ 已应用')
      : '待应用';
    const statusClass = d.applied ? 'applied' : 'pending';

    return `
    <div class="diff-card ${d.applied ? 'applied' : ''}" id="diff-${d.id}">
      <div class="diff-header" onclick="toggleDiff('${d.id}')">
        <div style="flex:1;overflow:hidden">
          <div class="diff-file">${escHtml(d.filePath)}</div>
          <div class="diff-desc" style="margin-top:3px">${escHtml(d.description||'')}</div>
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
        ${!d.applied
          ? `<div class="diff-actions">
               <button class="btn-danger" onclick="deleteDiff('${d.id}')">忽略</button>
               <button class="btn-primary" onclick="applyDiffById('${d.id}')">应用此修改</button>
             </div>`
          : `<div class="diff-actions" style="color:var(--accent);font-size:12px;font-family:var(--font-mono)">
               ${d.autoApplied ? '🤖 AI 已直接修改文件' : '✓ 已应用'}
               ${d.backupPath ? `<br>备份: ${escHtml(d.backupPath)}` : ''}
               ${d.backupPath ? `<button class="btn-danger" style="margin-left:10px" onclick="rollbackDiff('${d.id}')">⏪ 回滚</button>` : ''}
             </div>`}
      </div>
    </div>
  `;
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
document.querySelectorAll('.modal-overlay').forEach(o => {
  o.addEventListener('click', e => { if (e.target === o) o.style.display = 'none'; });
});
