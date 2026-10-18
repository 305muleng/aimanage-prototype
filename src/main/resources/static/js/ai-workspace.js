import { createHttpAiApi, validateReview, validateWorkspace } from './ai-api.js';

const root = document.getElementById('ai-workspace');
if (root) initialize(root);

function initialize(root) {
    const $ = id => root.querySelector(`#${id}`);
    const roleAllowsReview = root.dataset.canReview === 'true';
    let canReview = false;
    const displayName = root.dataset.displayName || '当前用户';
    const csrfHeader = document.querySelector('meta[name="_csrf_header"]')?.content;
    const csrfToken = document.querySelector('meta[name="_csrf"]')?.content;
    const api = createHttpAiApi({
        ...window.aimanageAiConfig,
        csrf: window.aimanageAiConfig?.csrf ?? { headerName: csrfHeader, token: csrfToken }
    });
    let workspace = null;
    let connected = false;
    let activeTab = 'answer';
    let busy = false;
    let submittingQuestion = false;
    let lastMessage = null;
    let reviewContext = null;
    let publishId = null;
    const errors = new Map();
    const labels = { PENDING_REVIEW: '待审核', APPROVED: '已采纳', REJECTED: '已否决', PUBLISHED: '已入库' };
    const actionLabels = { ACCEPT: '采纳', MODIFY: '修改后采纳', REJECT: '否决' };

    function element(tag, text, className) {
        const node = document.createElement(tag);
        if (text != null) node.textContent = String(text); // 模型和用户文本一律按纯文本渲染。
        if (className) node.className = className;
        return node;
    }
    function empty(parent, text) { parent.append(element('p', text, 'ai-empty')); }
    function button(text, action) {
        const node = element('button', text, 'notion-button');
        node.type = 'button'; node.disabled = busy; node.addEventListener('click', action);
        return node;
    }
    function lock(value) {
        busy = value;
        root.setAttribute('aria-busy', String(value));
        // 接口未连接不锁定输入；只在本次提问提交期间冻结文本，避免误清除新输入。
        $('ai-question').disabled = value && submittingQuestion;
        $('ai-ask').disabled = value || !$('ai-question').value.trim();
        for (const id of ['ai-review-submit', 'ai-review-cancel', 'ai-publish-confirm', 'ai-publish-cancel', 'ai-question-retry', 'ai-load-retry']) $(id).disabled = value;
        $('ai-review-reason').disabled = value;
        $('ai-modified-text').disabled = value;
        root.querySelectorAll('.ai-decision-actions button').forEach(node => { node.disabled = value || !connected; });
        root.querySelectorAll('.ai-decision .ai-error button').forEach(node => { node.disabled = value; });
    }
    function hintError(id, text) { $(id).textContent = text; $(id).hidden = !text; }
    function sourceIds(parent, item) {
        const parts = [];
        if (item.sourceMessageIds?.length) parts.push(`消息：${item.sourceMessageIds.join('、')}`);
        if (item.sourceTaskIds?.length) parts.push(`任务：${item.sourceTaskIds.join('、')}`);
        if (item.riskIds?.length) parts.push(`风险：${item.riskIds.join('、')}`);
        if (item.referencesSnapshot) parts.push('依据任务快照');
        if (parts.length) parent.append(element('small', parts.join('；'), 'ai-source-ids'));
    }
    function evidenceList(parent, title, items) {
        parent.append(element('h3', title));
        if (!items?.length) { empty(parent, '暂无内容'); return; }
        const list = element('ul');
        for (const item of items) {
            const node = element('li', typeof item === 'string' ? item : item.text);
            if (typeof item !== 'string') sourceIds(node, item);
            list.append(node);
        }
        parent.append(list);
    }
    function sources(parent, items) {
        if (!items?.length) { empty(parent, '暂无引用'); return; }
        for (const source of items) {
            const card = element('article', null, 'ai-result-card');
            card.append(element('strong', `[${source.number ?? '—'}] ${source.source || '未命名来源'}`));
            card.append(element('p', source.text || '未提供摘录', 'ai-preserve'));
            let url;
            try { url = new URL(source.url); } catch { /* 没有有效 URL 时只展示来源。 */ }
            if (url && ['https:', 'http:'].includes(url.protocol)) {
                const link = element('a', '查看来源'); link.href = url.href; link.target = '_blank'; link.rel = 'noopener noreferrer'; card.append(link);
            }
            if (source.decision) {
                card.append(element('small', `决策 ${source.decision.decisionId} · ${source.decision.simulation ? '模拟材料' : '项目材料'}`));
                card.append(element('small', `审核人：${source.decision.reviewer || '未提供'}；理由：${source.decision.reason || '未提供'}`));
            }
            parent.append(card);
        }
    }
    function renderMessages() {
        const list = $('ai-messages'); list.replaceChildren();
        if (!workspace?.messages?.length) empty(list, '暂无讨论消息');
        for (const message of workspace?.messages ?? []) {
            const card = element('article', null, `ai-message${message.assistant ? ' is-assistant' : ''}`);
            const heading = element('header'); heading.append(element('strong', message.speaker || '成员'));
            if (message.createdAt && !Number.isNaN(Date.parse(message.createdAt))) {
                const time = element('time', new Date(message.createdAt).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }));
                time.dateTime = message.createdAt; heading.append(time);
            }
            card.append(heading, element('p', message.content));
            if (message.delivery) card.append(element('small', message.delivery, 'ai-hint'));
            list.append(card);
        }
        list.scrollTop = list.scrollHeight;
    }
    function renderResult() {
        const panel = $('ai-result-panel'); panel.replaceChildren();
        panel.setAttribute('aria-labelledby', `ai-tab-${activeTab}`);
        root.querySelectorAll('[data-result-tab]').forEach(tab => {
            const selected = tab.dataset.resultTab === activeTab;
            tab.setAttribute('aria-selected', String(selected)); tab.tabIndex = selected ? 0 : -1;
        });
        if (!workspace) { empty(panel, '暂无结果'); return; }
        if (activeTab === 'answer') {
            panel.append(element('p', workspace.answer?.answer || '暂无回答', 'ai-preserve'));
            panel.append(element('h3', '引用来源')); sources(panel, workspace.answer?.sources);
        } else if (activeTab === 'sources') {
            sources(panel, workspace.answer?.sources);
        } else if (activeTab === 'minutes') {
            if (!workspace.minutes) { empty(panel, '暂无纪要'); return; }
            for (const [key, title] of [['summary', '讨论要点'], ['decisions', '候选决定'], ['suggestions', '建议']]) evidenceList(panel, title, workspace.minutes[key]);
            evidenceList(panel, '行动项', workspace.minutes.actionItems?.map(item => ({ ...item, text: `${item.task}；负责人：${item.owner || '待确认'}；期限：${item.deadline || '待确认'}` })));
            evidenceList(panel, '待确认问题', workspace.minutes.openQuestions);
        } else if (activeTab === 'requirements') {
            const draft = workspace.requirements?.result;
            if (!draft) { empty(panel, '暂无需求草稿'); return; }
            panel.append(element('h3', '用户故事'));
            if (!draft.userStories?.length) empty(panel, '暂无用户故事');
            for (const story of draft.userStories ?? []) {
                const card = element('article', null, 'ai-result-card');
                card.append(element('strong', `${story.id} · ${story.title}`), element('p', `作为${story.role}，我希望${story.goal}，以便${story.benefit}。`));
                evidenceList(card, '验收标准', story.acceptanceCriteria); panel.append(card);
            }
            evidenceList(panel, '开发任务', draft.developmentTasks?.map(item => `${item.id} · ${item.title}：${item.description}（关联故事：${item.storyIds?.join('、') || '未提供'}）`));
            evidenceList(panel, '待确认问题', draft.openQuestions);
            panel.append(element('h3', '草稿引用')); sources(panel, workspace.requirements?.grounding?.sources);
        } else if (activeTab === 'risks') {
            if (!workspace.risks) { empty(panel, '暂无风险建议'); return; }
            evidenceList(panel, '风险', workspace.risks.risks?.map(item => ({ ...item, text: `${item.id} · ${item.observation} 可能影响：${item.potentialImpact}` })));
            evidenceList(panel, '信息缺口', workspace.risks.informationGaps);
            evidenceList(panel, '协调建议（未执行）', workspace.coordination?.recommendations?.map(item => ({ ...item, text: `${item.title}：${item.rationale}` })));
        }
    }
    function renderDecisions() {
        const list = $('ai-decisions-list'); list.replaceChildren();
        if (!workspace?.candidates?.length) { empty(list, '暂无候选决策'); return; }
        for (const candidate of workspace.candidates) {
            const card = element('article', null, 'ai-decision');
            const heading = element('header'); heading.append(element('strong', candidate.id), element('span', labels[candidate.status] || '未知状态', 'status-tag'));
            card.append(heading, element('p', candidate.approvedText ?? candidate.originalText, 'ai-preserve'));
            if (candidate.simulation) card.append(element('small', '模拟材料', 'ai-hint'));
            const details = element('details'); details.append(element('summary', '查看原稿与依据'));
            details.append(element('p', `AI 原稿：${candidate.originalText}`, 'ai-preserve'));
            for (const message of candidate.sourceMessages ?? []) details.append(element('p', `${message.id} · ${message.speaker}：${message.content}`, 'ai-preserve'));
            card.append(details);
            if (candidate.review) {
                card.append(element('p', `审核：${actionLabels[candidate.review.action] || candidate.review.action}；审核人：${candidate.review.reviewer || '未提供'}`, 'ai-hint'));
                card.append(element('p', `理由：${candidate.review.reason}`, 'ai-preserve'));
            }
            const pendingError = errors.get(candidate.id);
            if (canReview && !pendingError?.outcomeUnknown && pendingError?.status !== 409) {
                const actions = element('div', null, 'ai-decision-actions');
                if (candidate.status === 'PENDING_REVIEW') {
                    for (const action of ['ACCEPT', 'MODIFY', 'REJECT']) actions.append(button(actionLabels[action], () => openReview(candidate, action)));
                } else if (candidate.status === 'APPROVED') {
                    actions.append(button(errors.has(candidate.id) ? '重试入库' : '入库', () => openPublish(candidate)));
                }
                card.append(actions);
            }
            const error = errors.get(candidate.id);
            if (error) {
                const alert = element('div', error.message, 'ai-error'); alert.setAttribute('role', 'alert');
                if (error.outcomeUnknown || error.status === 409) alert.append(button('刷新状态', load));
                card.append(alert);
            }
            list.append(card);
        }
    }
    async function load() {
        if (busy) return;
        connected = false; lock(true); $('ai-load-error').hidden = true; $('ai-load-status').hidden = false;
        try {
            const loaded = validateWorkspace(await api.load());
            workspace = loaded; lastMessage = null; errors.clear();
            connected = true;
            canReview = roleAllowsReview && loaded.permissions.reviewDecisions;
            $('ai-project-name').textContent = loaded.projectName || '当前项目';
            $('ai-review-permission').textContent = canReview ? '' : '只读';
            renderMessages(); renderResult(); renderDecisions();
            return true;
        } catch (error) {
            $('ai-load-error').hidden = false;
            $('ai-load-error-text').textContent = error.message || '页面加载失败。';
            return false;
        } finally { $('ai-load-status').hidden = true; lock(false); }
    }
    async function ask() {
        if (busy || !$('ai-question').value.trim()) return;
        $('ai-question-error').hidden = true;
        if (!connected) {
            $('ai-request-status').textContent = '正在连接…';
            const loaded = await load();
            $('ai-request-status').textContent = '';
            if (!loaded) {
                $('ai-question-error').hidden = false;
                $('ai-question-error-text').textContent = $('ai-load-error-text').textContent || '暂时无法连接 AI 服务，请重试。';
                $('ai-load-error').hidden = true;
                $('ai-question').focus();
                return; // 无真实会话时不发送提问，也不在客户端创建会话编号。
            }
        }
        // 连接期间输入仍可编辑，发送时读取最新内容。
        const question = $('ai-question').value.trim();
        if (!question) return;
        submittingQuestion = true;
        lock(true); $('ai-question-error').hidden = true; $('ai-request-status').textContent = '正在等待 AI 回复…';
        if (!lastMessage || lastMessage.content !== question || lastMessage.delivery === '已回复') {
            lastMessage = { id: `local-${Date.now()}`, speaker: displayName, content: question, createdAt: new Date().toISOString() };
            workspace.messages.push(lastMessage);
        }
        lastMessage.delivery = '等待回复'; renderMessages();
        try {
            const answer = await api.ask({ conversationId: workspace.conversationId, question });
            if (typeof answer?.answer !== 'string' || !Array.isArray(answer.sources)) throw new Error('暂时无法读取回答，请重试。');
            workspace.answer = answer; lastMessage.delivery = '已回复';
            workspace.messages.push({ id: `reply-${Date.now()}`, speaker: 'AI 助手', content: answer.answer, assistant: true, createdAt: new Date().toISOString() });
            // 只有后端实际返回的结果才更新页面，不在客户端生成纪要、决策或成功状态。
            for (const field of ['minutes', 'requirements', 'risks', 'coordination']) {
                if (Object.hasOwn(answer, field)) workspace[field] = answer[field];
            }
            if (Array.isArray(answer.candidates)) workspace.candidates = answer.candidates;
            $('ai-question').value = ''; activeTab = 'answer'; renderResult(); renderDecisions();
            $('ai-request-status').textContent = '';
        } catch (error) {
            lastMessage.delivery = '回复失败'; $('ai-request-status').textContent = '';
            $('ai-question-error').hidden = false; $('ai-question-error-text').textContent = error.message || '请求失败，请重试。';
        } finally { renderMessages(); submittingQuestion = false; lock(false); }
    }
    function openReview(candidate, action) {
        if (busy || !connected || !canReview || candidate.status !== 'PENDING_REVIEW') return;
        const error = errors.get(candidate.id);
        if (error?.outcomeUnknown || error?.status === 409) { load(); return; }
        reviewContext = { id: candidate.id, action };
        $('ai-review-title').textContent = `${actionLabels[action]}决策`;
        $('ai-review-original').textContent = candidate.originalText;
        $('ai-modify-field').hidden = action !== 'MODIFY'; $('ai-modified-text').required = action === 'MODIFY';
        $('ai-modified-text').value = action === 'MODIFY' ? candidate.originalText : '';
        $('ai-review-reason').value = ''; hintError('ai-review-error', '');
        $('ai-review-submit').textContent = '确认提交'; $('ai-review-dialog').showModal();
        (action === 'MODIFY' ? $('ai-modified-text') : $('ai-review-reason')).focus();
    }
    async function submitReview(event) {
        event.preventDefault(); if (busy || !reviewContext || !canReview) return;
        const { id, action } = reviewContext;
        const candidate = workspace.candidates.find(item => item.id === id);
        const payload = { action, reason: $('ai-review-reason').value.trim(), modifiedText: action === 'MODIFY' ? $('ai-modified-text').value.trim() : null };
        try { validateReview(candidate, payload); } catch (error) { hintError('ai-review-error', error.message); return; }
        lock(true); hintError('ai-review-error', ''); $('ai-review-submit').textContent = '正在提交…';
        try {
            const updated = await api.review(id, payload);
            if (updated?.id !== id || updated.status !== (action === 'REJECT' ? 'REJECTED' : 'APPROVED')) {
                const error = new Error('审核结果待确认，请刷新状态。'); error.outcomeUnknown = true; throw error;
            }
            workspace.candidates = workspace.candidates.map(item => item.id === id ? updated : item);
            errors.delete(id); $('ai-review-dialog').close();
        } catch (error) {
            if (error.outcomeUnknown || error.status === 409) {
                errors.set(id, error); $('ai-review-dialog').close();
            } else hintError('ai-review-error', error.message || '审核失败，请重试。');
        } finally { lock(false); $('ai-review-submit').textContent = '重试提交'; renderDecisions(); }
    }
    function openPublish(candidate) {
        if (busy || !connected || !canReview) return;
        const error = errors.get(candidate.id);
        if (error?.outcomeUnknown || error?.status === 409) { load(); return; }
        publishId = candidate.id; $('ai-publish-text').textContent = candidate.approvedText;
        $('ai-publish-dialog').showModal();
    }
    async function publish() {
        if (busy || !publishId || !canReview) return;
        const id = publishId; lock(true); $('ai-publish-confirm').textContent = '正在入库…';
        try {
            const result = await api.publish(id);
            if (result?.decision?.id !== id || result.decision.status !== 'PUBLISHED') {
                const error = new Error('入库结果待确认，请刷新状态。'); error.outcomeUnknown = true; throw error;
            }
            workspace.candidates = workspace.candidates.map(item => item.id === id ? result.decision : item); errors.delete(id);
        } catch (error) { errors.set(id, error); }
        finally { lock(false); $('ai-publish-dialog').close(); $('ai-publish-confirm').textContent = '确认入库'; renderDecisions(); }
    }

    $('ai-question-form').addEventListener('submit', event => { event.preventDefault(); ask(); });
    $('ai-question').addEventListener('input', () => { $('ai-ask').disabled = busy || !$('ai-question').value.trim(); });
    $('ai-question-retry').addEventListener('click', ask);
    $('ai-load-retry').addEventListener('click', load);
    $('ai-review-form').addEventListener('submit', submitReview);
    $('ai-review-cancel').addEventListener('click', () => $('ai-review-dialog').close());
    $('ai-publish-cancel').addEventListener('click', () => $('ai-publish-dialog').close());
    $('ai-publish-confirm').addEventListener('click', publish);
    for (const id of ['ai-review-dialog', 'ai-publish-dialog']) $(id).addEventListener('cancel', event => { if (busy) event.preventDefault(); });
    const tabs = [...root.querySelectorAll('[data-result-tab]')];
    tabs.forEach((tab, index) => {
        tab.addEventListener('click', () => { activeTab = tab.dataset.resultTab; renderResult(); });
        tab.addEventListener('keydown', event => {
            const next = event.key === 'ArrowRight' ? (index + 1) % tabs.length : event.key === 'ArrowLeft' ? (index + tabs.length - 1) % tabs.length : event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : null;
            if (next != null) { event.preventDefault(); tabs[next].click(); tabs[next].focus(); }
        });
    });
    renderMessages(); renderResult(); renderDecisions(); load();
}
