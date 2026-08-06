document.addEventListener('DOMContentLoaded', () => {
    const cards = Array.from(document.querySelectorAll('[data-tool-card]'));
    if (cards.length === 0) {
        return;
    }

    const groups = Array.from(document.querySelectorAll('[data-tool-group]'));
    const searchInput = document.getElementById('toolSearch');
    const clearSearchButton = document.getElementById('clearToolSearch');
    const sourceButtons = Array.from(document.querySelectorAll('[data-source-filter]'));
    const statusSelect = document.getElementById('toolStatusFilter');
    const visibleCount = document.getElementById('visibleToolCount');
    const noResults = document.getElementById('agentToolNoResults');
    const resetButton = document.getElementById('resetToolFilters');

    const state = {
        source: 'ALL',
        status: 'ALL',
        query: ''
    };

    const normalize = value => (value || '').toLocaleLowerCase('zh-CN').trim();

    function applyFilters() {
        let matches = 0;

        cards.forEach(card => {
            const sourceMatches = state.source === 'ALL' || card.dataset.source === state.source;
            const statusMatches = state.status === 'ALL' || card.dataset.status === state.status;
            const queryMatches = !state.query || normalize(card.dataset.searchText).includes(state.query);
            const visible = sourceMatches && statusMatches && queryMatches;
            card.hidden = !visible;
            if (visible) {
                matches += 1;
            }
        });

        groups.forEach(group => {
            const visibleCards = group.querySelectorAll('[data-tool-card]:not([hidden])').length;
            group.hidden = visibleCards === 0;
            const count = group.querySelector('.agent-tool-group-count strong');
            if (count) {
                count.textContent = String(visibleCards);
            }
        });

        visibleCount.textContent = String(matches);
        noResults.hidden = matches !== 0;
        clearSearchButton.hidden = !state.query;
    }

    sourceButtons.forEach(button => {
        button.addEventListener('click', () => {
            state.source = button.dataset.sourceFilter;
            sourceButtons.forEach(item => {
                const active = item === button;
                item.classList.toggle('is-active', active);
                item.setAttribute('aria-pressed', String(active));
            });
            applyFilters();
        });
    });

    searchInput.addEventListener('input', () => {
        state.query = normalize(searchInput.value);
        applyFilters();
    });

    clearSearchButton.addEventListener('click', () => {
        searchInput.value = '';
        state.query = '';
        searchInput.focus();
        applyFilters();
    });

    statusSelect.addEventListener('change', () => {
        state.status = statusSelect.value;
        applyFilters();
    });

    resetButton.addEventListener('click', () => {
        state.source = 'ALL';
        state.status = 'ALL';
        state.query = '';
        searchInput.value = '';
        statusSelect.value = 'ALL';
        sourceButtons.forEach(item => {
            const active = item.dataset.sourceFilter === 'ALL';
            item.classList.toggle('is-active', active);
            item.setAttribute('aria-pressed', String(active));
        });
        applyFilters();
        searchInput.focus();
    });

    document.querySelectorAll('.agent-tool-toggle-form').forEach(form => {
        form.addEventListener('submit', () => {
            const toggle = form.querySelector('button');
            if (toggle) {
                toggle.disabled = true;
                toggle.setAttribute('aria-busy', 'true');
            }
        });
    });

    applyFilters();
});
