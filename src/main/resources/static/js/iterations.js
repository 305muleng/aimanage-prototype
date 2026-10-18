(() => {
  const root = document.querySelector('.iterations-page');
  if (!root) return;
  const tabs = [...root.querySelectorAll('[data-pane]')];
  const panels = [...root.querySelectorAll('[data-panel]')];
  function showPane(name) {
    if (!tabs.some(tab => tab.dataset.pane === name)) name = 'scope';
    tabs.forEach(tab => tab.setAttribute('aria-pressed', String(tab.dataset.pane === name)));
    panels.forEach(panel => {panel.hidden = panel.dataset.panel !== name;});
  }
  tabs.forEach(tab => tab.addEventListener('click', () => {
    history.replaceState(null, '', '#' + tab.dataset.pane); showPane(tab.dataset.pane);
  }));
  showPane(location.hash.slice(1));
  window.addEventListener('hashchange', () => showPane(location.hash.slice(1)));
  root.querySelectorAll('[data-bulk]').forEach(form => {
    const boxes = [...form.querySelectorAll('input[name="storyIds"]')];
    const all = form.querySelector('[data-select-all]');
    function refresh() {
      const selected = boxes.filter(box => box.checked).length;
      const counter = form.querySelector('[data-selection-count]');
      if (counter) counter.textContent = '已选 ' + selected + ' 个故事';
      const submit = form.querySelector('[data-bulk-submit]');
      if (submit) submit.disabled = selected === 0;
      if (all) {all.checked = boxes.length > 0 && selected === boxes.length; all.indeterminate = selected > 0 && selected < boxes.length;}
    }
    boxes.forEach(box => box.addEventListener('change', refresh));
    all?.addEventListener('change', () => {boxes.forEach(box => {box.checked = all.checked;}); refresh();});
    form.querySelector('[data-story-search]')?.addEventListener('input', event => {
      const query = event.target.value.trim().toLowerCase();
      form.querySelectorAll('[data-story-title]').forEach(row => {
        row.hidden = !row.dataset.storyTitle.toLowerCase().includes(query);
        if (row.hidden) row.querySelector('input').checked = false;
      });
      refresh();
    });
    refresh();
  });
  const finishForm = root.querySelector('.it-finish-form');
  function finishProgress() {
    if (!finishForm) return;
    const choices = [...finishForm.querySelectorAll('[data-resolution]')];
    const remaining = choices.filter(select => !select.value || (select.value === 'MOVE' && !select.closest('.it-resolution').querySelector('[data-target] select').value)).length;
    finishForm.querySelector('[data-finish-count]').textContent = choices.length ? (remaining ? '还有 ' + remaining + ' 个故事待安排' : '已安排完毕') : '';
  }
  root.querySelector('[data-return-scope]')?.addEventListener('click', () => {history.replaceState(null, '', '#scope');showPane('scope');});
  finishForm?.addEventListener('change', finishProgress);
  root.querySelectorAll('[data-resolution]').forEach(select => {
    const container = select.closest('.it-resolution').querySelector('[data-target]');
    function refresh() {
      container.hidden = select.value !== 'MOVE';
      container.querySelector('select').required = select.value === 'MOVE';
      container.querySelector('select').disabled = select.value !== 'MOVE';
    }
    select.addEventListener('change', refresh); refresh();
  });
  finishProgress();
})();
