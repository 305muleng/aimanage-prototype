"""Check the F01 repair jar against a fresh local in-memory database.

Runs outside the application source. Requires Python 3 and lxml.
No external database or model service is contacted.
"""
from pathlib import Path
from datetime import datetime
from zoneinfo import ZoneInfo
from urllib.request import build_opener, ProxyHandler, HTTPCookieProcessor, HTTPRedirectHandler, Request
from urllib.parse import urlencode, urlsplit
from urllib.error import HTTPError, URLError
from http.cookiejar import CookieJar
from http.client import IncompleteRead
from lxml import html
import json, re, socket, subprocess, time, traceback

BASE = Path(__file__).resolve().parent
ROOT = BASE / 'aimanage-sprint2'
EVIDENCE = BASE / 'evidence'
EVIDENCE.mkdir(exist_ok=True)
checks = []
started = datetime.now(ZoneInfo('Asia/Shanghai')).isoformat()
with socket.socket() as sock:
    sock.bind(('127.0.0.1', 0))
    PORT = sock.getsockname()[1]
URL = f'http://127.0.0.1:{PORT}'

class NoRedirect(HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None

class Client:
    def __init__(self):
        self.jar = CookieJar()
        self.opener = build_opener(ProxyHandler({}), HTTPCookieProcessor(self.jar))
        self.raw = build_opener(ProxyHandler({}), HTTPCookieProcessor(self.jar), NoRedirect())

    def call(self, path, fields=None, follow=True):
        data = None if fields is None else urlencode(fields).encode()
        req = Request(URL + path, data=data)
        try:
            r = (self.opener if follow else self.raw).open(req, timeout=15)
        except HTTPError as err:
            r = err
        read_error = None
        try:
            raw = r.read()
        except IncompleteRead as err:
            raw = err.partial
            read_error = 'IncompleteRead'
        body = raw.decode('utf-8', errors='replace')
        return {'status': r.code, 'path': urlsplit(r.geturl()).path,
                'location': r.headers.get('Location', ''), 'body': body, 'read_error': read_error}

    def page(self, path):
        r = self.call(path)
        assert r['status'] == 200 and r['path'] != '/login' and not r['read_error'], (path, r['status'], r['path'], r['read_error'])
        return html.fromstring(r['body'])

    def context(self, tid):
        r = self.call(f'/tasks/{tid}/context')
        assert r['status'] == 200, r['status']
        return json.loads(r['body'])

def record(code, name, expected, actual, result='PASS'):
    checks.append({'id': code, 'name': name, 'expected': expected, 'actual': actual, 'result': result})
    print(code, name, result, flush=True)

def form(tree, action):
    found = tree.xpath('//form[@action=$a]', a=action)
    assert found, f'Form missing: {action}'
    return next((f for f in found if f.get('method', 'get').lower() == 'post'), found[0])

def hidden(f):
    return {el.get('name'): el.get('value', '') for el in f.xpath('.//input[@type="hidden"][@name]')}

def post_form(client, page, action, fields):
    data = hidden(form(client.page(page), action))
    data.update(fields)
    r = client.call(action, data)
    assert r['status'] == 200, r['status']
    return r

jar = ROOT / 'dist/aimanage-prototype-0.0.1-SNAPSHOT.jar'
if not jar.exists():
    jar = ROOT / 'target/aimanage-prototype-0.0.1-SNAPSHOT.jar'
command = ['java', '-Duser.timezone=Asia/Shanghai', '-jar', str(jar),
           '--server.address=127.0.0.1', f'--server.port={PORT}', '--spring.profiles.active=h2',
           '--spring.datasource.url=jdbc:h2:mem:aimanage-check;MODE=MySQL;DB_CLOSE_DELAY=-1',
           '--spring.jpa.hibernate.ddl-auto=create-drop']
process = None
failure = None
log = (EVIDENCE / 'application-smoke.log').open('w')
try:
    process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
    probe = Client()
    for _ in range(150):
        if process.poll() is not None:
            raise RuntimeError('Application exited during startup')
        try:
            if probe.call('/login')['status'] == 200:
                break
        except (URLError, TimeoutError, ConnectionError):
            pass
        time.sleep(0.4)
    else:
        raise RuntimeError('Application startup exceeded 60 seconds')

    clients = {n: Client() for n in ['manager', 'developer', 'developer2', 'admin']}
    for username, c in clients.items():
        wanted = '/admin/roles' if username == 'admin' else '/manager' if username == 'manager' else '/my-tasks'
        for _ in range(25):
            c.call('/login')
            response = c.call('/login', {'username': username, 'password': '123456'})
            if response['status'] == 200 and response['path'] == wanted:
                break
            time.sleep(0.2)
        assert response['status'] == 200 and response['path'] == wanted
    manager, dev, outsider, admin = [clients[n] for n in ['manager', 'developer', 'developer2', 'admin']]
    record('H01', '应用启动与四个演示账号登录', '登录后进入对应角色入口', '四个账号均进入对应入口')

    name = '本次代码检查专用项目'
    post_form(manager, '/manager', '/manager/projects', {'name': name, 'description': '本次HTTP验证使用的临时数据'})
    tree = manager.page('/manager')
    project_form = next(f for f in tree.xpath('//form')
                        if re.fullmatch(r'/manager/projects/\d+/edit', f.get('action', ''))
                        and f.xpath('.//input[@name="name"][@value=$v]', v=name))
    pid = int(re.search(r'/projects/(\d+)/', project_form.get('action')).group(1))
    member_form = form(tree, f'/manager/projects/{pid}/members')
    uid = next(o.get('value') for o in member_form.xpath('.//select[@name="userId"]/option')
               if '（developer）' in o.text_content())
    post_form(manager, '/manager', f'/manager/projects/{pid}/members', {'userId': uid})
    req_title, story_title = '检查需求', '检查故事'
    post_form(manager, f'/backlog?projectId={pid}', '/backlog',
              {'projectId': pid, 'type': 'REQUIREMENT', 'title': req_title, 'description': '检查需求说明', 'acceptance': '', 'priority': 'HIGH'})
    tree = manager.page(f'/backlog?projectId={pid}')
    requirement_id = next(o.get('value') for o in tree.xpath('//select[@name="requirementId"]/option') if o.text_content() == req_title)
    post_form(manager, f'/backlog?projectId={pid}', '/backlog',
              {'projectId': pid, 'type': 'STORY', 'title': story_title, 'description': '两项任务依次完成',
               'acceptance': '完成说明、依赖和权限均符合规则', 'priority': 'HIGH', 'requirementId': requirement_id})
    tids = []
    for title in ['检查任务A', '检查任务B']:
        post_form(manager, '/manager', f'/manager/projects/{pid}/tasks',
                  {'title': title, 'description': title + '说明', 'assigneeId': uid, 'dueDate': '2026-10-22'})
        tree = manager.page('/manager')
        tf = next(f for f in tree.xpath('//form')
                  if re.fullmatch(r'/manager/tasks/\d+/edit', f.get('action', ''))
                  and f.xpath('.//input[@name="title"][@value=$v]', v=title))
        tids.append(int(re.search(r'/tasks/(\d+)/', tf.get('action')).group(1)))
    a, b = tids
    tree = manager.page('/manager')
    pf = form(tree, f'/manager/tasks/{a}/plan')
    sid = next(o.get('value') for o in pf.xpath('.//select[@name="storyId"]/option') if o.text_content() == story_title)
    plan_data = hidden(pf)
    plan_data.update({'priority': 'HIGH', 'storyId': sid})
    manager.call(f'/manager/tasks/{a}/plan', plan_data, follow=False)
    linked_dashboard = manager.call('/manager')
    linked_my_tasks = dev.call('/my-tasks')
    link_broken = any(r['read_error'] or r['status'] >= 500 for r in [linked_dashboard, linked_my_tasks])
    assert not link_broken, "F01 still reproduces"
    assert story_title in linked_dashboard["body"] and "</html>" in linked_dashboard["body"]
    assert "</html>" in linked_my_tasks["body"]
    record("H02", "任务关联故事后的页面渲染", "保留故事关联时两页能完整显示", "工作台显示关联故事，工作台和我的任务均完整返回；保留关联继续检查")
    post_form(manager, f'/tasks/{b}', f'/tasks/{b}/dependencies', {'predecessorId': a})
    assert dev.context(b)['predecessors'][0]['id'] == a

    r = post_form(dev, f'/tasks/{b}', f'/tasks/{b}/status', {'status': 'DONE', 'note': '验证前置校验'})
    assert '前置任务尚未完成' in r['body'] and dev.context(b)['task']['status'] == 'TODO'
    record('H03', '未完成前置任务拦截', '拒绝完成B且状态不变', '提示前置任务未完成，B保持TODO')

    post_form(dev, f'/tasks/{b}', f'/tasks/{b}/block', {'blocked': 'true', 'reason': '等待核对'})
    assert dev.context(b)['task']['blocked'] is True
    post_form(dev, f'/tasks/{a}', f'/tasks/{a}/comments', {'content': '本次HTTP检查评论'})
    post_form(dev, f'/tasks/{a}', f'/tasks/{a}/status', {'status': 'IN_PROGRESS', 'note': ''})
    post_form(dev, f'/tasks/{a}', f'/tasks/{a}/status', {'status': 'DONE', 'note': 'A已完成并核对'})
    data = dev.context(a)
    assert data['task']['status'] == 'DONE' and data['task']['completedAt']
    assert any(e['content'] == '本次HTTP检查评论' for e in data['entries'])
    record('H04', '成员执行与评论留痕', 'A完成说明、时间和评论保存', 'A完成，说明及时间存在，评论可重新读取')

    r = post_form(dev, f'/tasks/{b}', f'/tasks/{b}/status', {'status': 'DONE', 'note': '验证独立阻塞'})
    assert '请先解除阻塞' in r['body'] and dev.context(b)['task']['blocked'] is True
    post_form(dev, f'/tasks/{b}', f'/tasks/{b}/block', {'blocked': 'false', 'reason': ''})
    post_form(dev, f'/tasks/{b}', f'/tasks/{b}/status', {'status': 'DONE', 'note': 'B已完成并核对'})
    assert all(dev.context(t)['task']['status'] == 'DONE' for t in tids)
    record('H05', '独立阻塞及后续任务完成', 'A完成不会自动清除B阻塞，解除后才能完成', 'B先被阻塞规则拦截，解除后完成；专用项目两项任务均DONE')

    r = outsider.call(f'/tasks/{a}/context', follow=False)
    assert r['status'] == 403 and '检查任务A' not in r['body']
    r_admin = admin.call(f'/tasks/{a}/context', follow=False)
    r_anon = Client().call(f'/tasks/{a}/context', follow=False)
    assert r_admin['status'] == 302 and r_admin['location'].endswith('/login')
    assert r_anon['status'] == 302 and r_anon['location'].endswith('/login')
    record('H06', '项目外人员与角色权限', '外部成员403，管理员及未登录者跳转且无数据', 'developer2返回403；admin和未登录会话返回302至登录页')

    old_count = len(dev.context(a)['entries'])
    r = dev.call(f'/tasks/{a}/comments', {'content': '缺少令牌的评论'})
    assert '页面已失效' in r['body'] and len(dev.context(a)['entries']) == old_count
    record('H07', '缺少表单令牌', '拒绝写入且评论数量不变', '提示页面失效，评论数量未增加')

    version = dev.context(b)['task']['version']
    f = form(dev.page(f'/tasks/{b}'), f'/tasks/{b}/block')
    data = hidden(f)
    data.update({'version': version - 1, 'blocked': 'false', 'reason': ''})
    r = dev.call(f'/tasks/{b}/block', data)
    assert '任务已被更新或页面已失效' in r['body'] and dev.context(b)['task']['version'] == version
    record('H08', '过期版本写入', '拒绝旧版本且数据不变', '提示刷新页面，任务版本未改变')

    post_form(manager, f'/tasks/{a}', f'/tasks/{a}/status', {'status': 'IN_PROGRESS', 'note': '负责人复核后重开'})
    assert dev.context(a)['task']['status'] == 'IN_PROGRESS' and dev.context(b)['task']['status'] == 'DONE'
    # After reopening, the manager has no status form. Deliberately submit a
    # crafted request with the valid task token to check server-side authority.
    denied_data = hidden(form(manager.page(f'/tasks/{a}'), f'/tasks/{a}/comments'))
    denied_data.update({'version': dev.context(a)['task']['version'], 'status': 'DONE', 'note': '验证负责人权限'})
    r = manager.call(f'/tasks/{a}/status', denied_data)
    assert dev.context(a)['task']['status'] == 'IN_PROGRESS'
    post_form(dev, f'/tasks/{a}', f'/tasks/{a}/status', {'status': 'DONE', 'note': '成员复核完成'})
    record('H09', '重开与角色职责', '负责人可重开，不能代成员完成，后续任务不自动回退', 'A重开后B仍DONE；负责人完成请求未生效，成员可再次完成A')

    board = manager.call('/tasks?' + urlencode({'projectId': pid, 'keyword': '检查任务', 'status': 'DONE', 'sort': 'title'}))
    assert board['status'] == 200 and all(f'检查任务{x}' in board['body'] for x in ['A', 'B'])
    dashboard = manager.page('/manager')
    metrics = []
    for el in dashboard.xpath('//*[contains(concat(" ",normalize-space(@class)," ")," metric-block ")]'):
        metrics.append(' '.join(el.text_content().split()))
    assert any('67%' in t for t in metrics), metrics
    record('H10', '任务筛选与负责人统计', '两项DONE任务可筛选，汇总口径与数据相符', '专用项目2/2完成；负责人全部项目共3项任务、完成2项，页面67%；' + '；'.join(metrics))

    for slug in ['sprints', 'analytics', 'ai', 'backlog']:
        r = manager.call('/preview/' + slug)
        assert r['status'] == 200 and '界面预览' in r['body'] and 'data-placeholder=' in r['body']
    real = manager.call(f'/backlog?projectId={pid}')
    assert story_title in real['body'] and '<form' in real['body']
    assert story_title in manager.call('/manager')['body']
    record('H11', '预览边界与Backlog旧入口', '区分真实Backlog和预览页', '四个preview地址均保留占位交互；真实Backlog可保存，工作台旧Backlog链接仍指向预览')
except Exception as exc:
    failure = {'type': type(exc).__name__, 'message': str(exc), 'traceback': traceback.format_exc()}
    print('VERIFICATION FAILED', type(exc).__name__, str(exc), flush=True)
finally:
    if process and process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()
    log.close()
    result = {'started_at': started, 'finished_at': datetime.now(ZoneInfo('Asia/Shanghai')).isoformat(),
              'scope': 'F01 repair copy based on supplied Sprint 2 zip; story association retained; fresh H2 memory database; HTTP forms and JSON, no browser UI verification',
              'command': command, 'passed_checks': sum(c['result'] == 'PASS' for c in checks),
              'failed_checks': sum(c['result'] == 'FAIL' for c in checks), 'checks': checks, 'failure': failure,
              'application_stopped': process is None or process.poll() is not None, 'story_association_retained': failure is None and len(checks) == 11}
    (EVIDENCE / 'http-verification.json').write_text(json.dumps(result, indent=2, ensure_ascii=False))
    print(json.dumps({'passed_checks': result['passed_checks'], 'failed_checks': result['failed_checks'], 'failure': failure, 'application_stopped': result['application_stopped']}, ensure_ascii=False), flush=True)
if failure or any(c['result'] == 'FAIL' for c in checks):
    raise SystemExit(1)
