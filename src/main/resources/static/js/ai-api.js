export const reviewActions = ['ACCEPT', 'MODIFY', 'REJECT'];

// 前端请求约定，不代表当前仓库已经实现这些 HTTP 接口。
// 后端路径不同时可通过 window.aimanageAiConfig.endpoints 集中覆盖。
export const aiEndpoints = Object.freeze({
    load: '/api/ai/workspace',
    ask: '/api/ai/questions',
    review: id => `/api/ai/decisions/${encodeURIComponent(id)}/review`,
    publish: id => `/api/ai/decisions/${encodeURIComponent(id)}/publish`
});

export function validateReview(candidate, { action, reason, modifiedText }) {
    if (!candidate || candidate.status !== 'PENDING_REVIEW') throw new Error('该决策不是待审核状态，请刷新状态。');
    if (!reviewActions.includes(action)) throw new Error('不支持的审核操作。');
    if (!reason?.trim()) throw new Error('请填写审核理由。');
    if (action === 'MODIFY' && (!modifiedText?.trim() || modifiedText.trim() === candidate.originalText.trim())) {
        throw new Error('请填写与原稿不同的完整修改内容。');
    }
    if (action !== 'MODIFY' && modifiedText != null) throw new Error('只有修改后采纳可以提交改稿。');
}

/** 前端数据约定，后端控制器负责将现有内部服务返回值适配为以下结构：
 * load() -> { projectName, conversationId, permissions: {reviewDecisions: boolean},
 *             messages: [{id, speaker, content, createdAt?, assistant?}],
 *             answer: {answer, sources} | null, minutes, requirements,
 *             risks, coordination, candidates: Candidate[] }
 * ask({conversationId, question}) -> {answer: string, sources: SourceSnippet[],
 *             minutes?, requirements?, risks?, coordination?, candidates?}
 * 可选字段只在服务端实际生成对应结果时返回；未返回的字段保留原内容。
 * review(id, {action, reason, modifiedText}) -> Candidate
 * publish(id) -> Publication {decision, imported}
 * HTTP 后端须从登录身份验证会话/项目归属和审核权限，禁止信任前端角色或审核人。
 * 页面不传服务器文件路径，不传 Java 服务对象，也不调用模型供应商。
 * 已有 Candidate/Publication/SourceSnippet 与 handoff/README.md 保持一致。
 * 状态转换必须由服务端完成；本模块不生成审核记录或本地成功状态。
 */
export function validateWorkspace(value) {
    if (!value || !Array.isArray(value.messages) || !Array.isArray(value.candidates)
        || typeof value.conversationId !== 'string' || !value.conversationId.trim()
        || typeof value.permissions?.reviewDecisions !== 'boolean') {
        throw new Error('数据加载失败，请重试。');
    }
    return value;
}

/** 通过 window.aimanageAiConfig = {endpoints?, csrf?} 适配后端。
 * endpoints = {load: URL, ask: URL, review: id=>URL, publish: id=>URL}；只允许同源调用。
 * 可传 csrf: {headerName, token}，其值来自后端生成的页面信息，不在代码中放密钥。
 * 四个接口都直接返回 JSON 对象；若后端使用统一 data 包装，在此处统一解包。
 */
export function createHttpAiApi({ endpoints = aiEndpoints, csrf, fetchImpl = globalThis.fetch, baseUrl = globalThis.location?.href } = {}) {
    async function request(operation, id, payload) {
        const entry = endpoints?.[operation];
        if (!entry) throw new Error('服务暂不可用，请稍后重试。');
        const address = typeof entry === 'function' ? entry(id) : entry;
        const url = new URL(address, baseUrl);
        if (url.origin !== new URL(baseUrl).origin) throw new Error('AI 接口必须与当前网站同源。');
        const write = operation !== 'load';
        const headers = { Accept: 'application/json' };
        if (write) headers['Content-Type'] = 'application/json';
        if (write && csrf?.token && csrf?.headerName) headers[csrf.headerName] = csrf.token;
        const controller = new AbortController();
        const timer = setTimeout(() => controller.abort(), 30000);
        try {
            const response = await fetchImpl(url.href, { method: write ? 'POST' : 'GET', credentials: 'same-origin', cache: 'no-store', headers,
                signal: controller.signal, ...(write ? { body: JSON.stringify(payload ?? {}) } : {}) });
            if (!response.ok) {
                const messages = { 401: '登录已失效，请重新登录。', 403: '你没有执行此操作的权限。', 404: 'AI 服务暂不可用，请稍后重试。', 409: '数据状态已变化，请刷新后查看。' };
                const error = new Error(messages[response.status] || '服务请求失败，请稍后重试。');
                error.status = response.status;
                // 写操作 5xx 的服务端结果也可能不确定，先查询再决定是否重提。
                error.outcomeUnknown = ['review', 'publish'].includes(operation) && response.status >= 500;
                throw error;
            }
            if (!(response.headers.get('content-type') || '').includes('application/json')) throw new Error('接口未返回约定的 JSON 数据。');
            return await response.json();
        } catch (error) {
            if (error.status) throw error;
            const safe = new Error('请求未完成，请检查连接后重试。');
            safe.outcomeUnknown = ['review', 'publish'].includes(operation);
            throw safe; // 不回显供应商响应、原始异常或密钥。
        } finally { clearTimeout(timer); }
    }
    return { mode: 'http', load: () => request('load'), ask: payload => request('ask', null, payload),
        review: (id, payload) => request('review', id, payload), publish: id => request('publish', id) };
}
