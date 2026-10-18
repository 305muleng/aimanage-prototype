import test from 'node:test';
import assert from 'node:assert/strict';
import { aiEndpoints, createHttpAiApi, validateReview, validateWorkspace } from '../../main/resources/static/js/ai-api.js';

const baseUrl = 'http://127.0.0.1:18081/preview/ai';
const pending = { id: 'D-1', originalText: '先实现任务创建。', status: 'PENDING_REVIEW' };
const accept = { action: 'ACCEPT', reason: '已核对讨论依据。', modifiedText: null };
const response = value => new Response(JSON.stringify(value), { headers: { 'Content-Type': 'application/json' } });

test('默认路径直接请求HTTP，不依赖演示适配器', async () => {
    const requests = [];
    const api = createHttpAiApi({ baseUrl, fetchImpl: async (url, options) => { requests.push({ url, options }); return response({}); } });
    await api.load(); await api.ask({ conversationId: 'C-1', question: '问题' });
    await api.review('D-1', accept); await api.publish('D-1');
    assert.deepEqual(requests.map(item => [new URL(item.url).pathname, item.options.method]), [
        ['/api/ai/workspace', 'GET'], ['/api/ai/questions', 'POST'],
        ['/api/ai/decisions/D-1/review', 'POST'], ['/api/ai/decisions/D-1/publish', 'POST']
    ]);
});
test('保留路径覆盖能力，决策编号编码后嵌入路径', async () => {
    assert.equal(aiEndpoints.review('D/a?b'), '/api/ai/decisions/D%2Fa%3Fb/review');
    let address;
    const api = createHttpAiApi({ baseUrl, endpoints: { load: '/team/workspace' }, fetchImpl: async url => { address = url; return response({}); } });
    await api.load(); assert.equal(new URL(address).pathname, '/team/workspace');
});
test('工作区要求会话、消息、候选列表和服务端审核权限', () => {
    const data = { conversationId: 'C-1', messages: [], candidates: [], permissions: { reviewDecisions: false } };
    assert.equal(validateWorkspace(data), data);
    for (const invalid of [null, {}, { ...data, conversationId: '' }, { ...data, permissions: {} }, { ...data, messages: null }]) assert.throws(() => validateWorkspace(invalid));
});
test('三种审核动作都要求非空理由', () => {
    for (const action of ['ACCEPT', 'MODIFY', 'REJECT']) {
        assert.throws(() => validateReview(pending, { action, reason: ' ', modifiedText: '修改后的内容' }), /理由/);
    }
});
test('修改后采纳要求非空且不同于原稿，其他动作不得带改稿', () => {
    for (const modifiedText of [' ', pending.originalText]) assert.throws(() => validateReview(pending, { action: 'MODIFY', reason: '核对后修改', modifiedText }));
    validateReview(pending, { action: 'MODIFY', reason: '补充评审要求', modifiedText: '先评审字段，再实现任务创建。' });
    assert.throws(() => validateReview(pending, { ...accept, modifiedText: '不允许附带改稿' }));
});
test('非待审核状态与未知动作不可提交审核', () => {
    for (const status of ['APPROVED', 'PUBLISHED', 'REJECTED']) assert.throws(() => validateReview({ ...pending, status }, accept));
    assert.throws(() => validateReview(pending, { ...accept, action: 'OTHER' }));
});
test('HTTP操作仅返回服务端数据，不在前端伪造采纳或入库状态', async () => {
    const data = { id: 'D-1', status: 'PENDING_REVIEW' };
    const api = createHttpAiApi({ baseUrl, fetchImpl: async () => response(data) });
    assert.deepEqual(await api.review('D-1', accept), data);
    assert.deepEqual(await api.publish('D-1'), data);
});
test('提问返回的纪要、需求、风险和候选决策可传给页面', async () => {
    const data = { answer: '回答', sources: [], minutes: { summary: [] }, requirements: null, risks: null, candidates: [] };
    const api = createHttpAiApi({ baseUrl, fetchImpl: async () => response(data) });
    assert.deepEqual(await api.ask({ conversationId: 'C-1', question: '问题' }), data);
});
test('携带同源登录凭据与CSRF，不提交伪造审核人', async () => {
    let captured;
    const api = createHttpAiApi({ baseUrl, csrf: { headerName: 'X-CSRF-TOKEN', token: 'test-token' }, fetchImpl: async (url, options) => {
        captured = options; return response({ id: 'D-1', status: 'APPROVED' });
    } });
    await api.review('D-1', accept);
    assert.equal(captured.credentials, 'same-origin'); assert.equal(captured.headers['X-CSRF-TOKEN'], 'test-token');
    assert.deepEqual(JSON.parse(captured.body), accept);
    assert.equal(JSON.parse(captured.body).reviewer, undefined);
});
test('缺少接口或跨域配置明确失败', async () => {
    await assert.rejects(createHttpAiApi({ baseUrl, endpoints: {} }).load(), /不可用/);
    await assert.rejects(createHttpAiApi({ baseUrl, endpoints: { load: 'https://other.test/api' } }).load(), /同源/);
});
for (const status of [401, 403, 404, 409, 500]) test(`HTTP ${status} 不回退假数据或泄露服务端正文`, async () => {
    const api = createHttpAiApi({ baseUrl, fetchImpl: async () => new Response('secret-provider-text', { status }) });
    await assert.rejects(api.load(), error => error.status === status && !error.message.includes('secret'));
});
test('审核/入库网络或5xx失败标记为结果未知，禁止盲目重复提交', async () => {
    for (const fetchImpl of [async () => { throw new Error('secret-provider-text'); }, async () => new Response('secret-provider-text', { status: 500 })]) {
        const api = createHttpAiApi({ baseUrl, fetchImpl });
        await assert.rejects(api.review('D-1', accept), error => error.outcomeUnknown === true && !error.message.includes('secret'));
        await assert.rejects(api.publish('D-1'), error => error.outcomeUnknown === true);
    }
});
test('登录HTML和错误JSON响应不能作为成功结果', async () => {
    for (const fetchImpl of [async () => new Response('<html>login</html>', { headers: { 'Content-Type': 'text/html' } }), async () => new Response('{', { headers: { 'Content-Type': 'application/json' } })]) {
        const api = createHttpAiApi({ baseUrl, fetchImpl });
        await assert.rejects(api.load());
        await assert.rejects(api.publish('D-1'), error => error.outcomeUnknown === true);
    }
});
test('请求失败可以再次请求，但不会自行构造结果', async () => {
    let attempts = 0;
    const api = createHttpAiApi({ baseUrl, fetchImpl: async () => {
        if (++attempts === 1) throw new Error('offline');
        return response({ answer: '服务端回答', sources: [] });
    } });
    await assert.rejects(api.ask({ conversationId: 'C-1', question: '问题' }));
    assert.equal((await api.ask({ conversationId: 'C-1', question: '问题' })).answer, '服务端回答');
});
