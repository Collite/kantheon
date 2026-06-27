class ReceiptsViewer {
    constructor() {
        this.receipts = [];
        this.page = 0;
        this.pageSize = 20;
        this.verifyStatus = null;

        this.tableBody = document.getElementById('receiptsBody');
        this.verifyBtn = document.getElementById('verifyBtn');
        this.verifyStatus = document.getElementById('verifyStatus');
        this.loadMoreBtn = document.getElementById('loadMoreBtn');

        this.init();
    }

    init() {
        if (this.verifyBtn) {
            this.verifyBtn.addEventListener('click', () => this.verify());
        }
        if (this.loadMoreBtn) {
            this.loadMoreBtn.addEventListener('click', () => this.loadMore());
        }
        this.loadReceipts();
    }

    async loadReceipts(reset = false) {
        if (reset) {
            this.receipts = [];
            this.page = 0;
        }

        try {
            const since = this.getSinceParam();
            const response = await fetch(`/api/receipts?since=${since}&limit=${this.pageSize}`);
            const data = await response.json();

            if (data.error) {
                this.showError(data.error);
                return;
            }

            const newReceipts = JSON.parse(data.receipts);
            this.receipts = this.receipts.concat(newReceipts);
            this.render();
        } catch (error) {
            this.showError('Failed to load receipts: ' + error.message);
        }
    }

    getSinceParam() {
        const sinceSelect = document.getElementById('sinceSelect');
        if (!sinceSelect) return '';

        const value = sinceSelect.value;
        if (value === 'all') return '';

        const now = new Date();
        let since;

        switch (value) {
            case 'hour':
                since = new Date(now - 60 * 60 * 1000);
                break;
            case 'day':
                since = new Date(now - 24 * 60 * 60 * 1000);
                break;
            case 'week':
                since = new Date(now - 7 * 24 * 60 * 60 * 1000);
                break;
            case 'month':
                since = new Date(now - 30 * 24 * 60 * 60 * 1000);
                break;
            default:
                return '';
        }

        return since.toISOString();
    }

    async verify() {
        if (!this.verifyStatus) return;

        this.verifyStatus.textContent = 'Verifying...';
        this.verifyStatus.className = 'verify-status verifying';

        try {
            const response = await fetch('/api/receipts/verify');
            const data = await response.json();

            if (data.ok) {
                this.verifyStatus.textContent = `Verified: ${data.records} records, hash: ${data.lastSeq.substring(0, 16)}...`;
                this.verifyStatus.className = 'verify-status ok';
            } else {
                this.verifyStatus.textContent = `Failed at seq ${data.recordSeq}: ${data.errors}`;
                this.verifyStatus.className = 'verify-status failed';
            }
        } catch (error) {
            this.verifyStatus.textContent = `Error: ${error.message}`;
            this.verifyStatus.className = 'verify-status failed';
        }
    }

    loadMore() {
        this.page++;
        this.loadReceipts();
    }

    render() {
        if (!this.tableBody) return;

        const start = 0;
        const end = (this.page + 1) * this.pageSize;
        const visibleReceipts = this.receipts.slice(start, end);

        this.tableBody.innerHTML = visibleReceipts.map(r => this.renderRow(r)).join('');

        if (this.loadMoreBtn) {
            this.loadMoreBtn.style.display = end < this.receipts.length ? 'block' : 'none';
        }
    }

    renderRow(receipt) {
        const okClass = receipt.ok ? 'ok' : 'failed';
        const okIcon = receipt.ok ? '✓' : '✗';

        return `
            <tr class="receipt-row ${okClass}" onclick="window.receiptsViewer.toggleDetail(this)">
                <td class="seq">${receipt.seq}</td>
                <td class="ts">${this.formatTs(receipt.ts)}</td>
                <td class="tool">${this.escapeHtml(receipt.tool)}</td>
                <td class="ok"><span class="badge ${okClass}">${okIcon}</span></td>
                <td class="duration">${receipt.durationMs}ms</td>
            </tr>
            <tr class="detail-row hidden">
                <td colspan="5">
                    <div class="detail-content">
                        <div class="detail-field">
                            <strong>Session ID:</strong>
                            <code>${this.escapeHtml(receipt.sessionId)}</code>
                        </div>
                        <div class="detail-field">
                            <strong>Turn ID:</strong>
                            <code>${this.escapeHtml(receipt.turnId)}</code>
                        </div>
                        <div class="detail-field">
                            <strong>Args (redacted):</strong>
                            <pre>${this.escapeHtml(receipt.argsRedacted)}</pre>
                        </div>
                        <div class="detail-field">
                            <strong>Result Hash:</strong>
                            <code>${this.escapeHtml(receipt.resultHash)}</code>
                        </div>
                        <div class="detail-field">
                            <strong>Risk:</strong>
                            <span class="risk-${receipt.risk}">${receipt.risk}</span>
                        </div>
                        <div class="detail-field">
                            <strong>Prev Hash:</strong>
                            <code>${this.escapeHtml(receipt.prevHash)}</code>
                        </div>
                        <div class="detail-field">
                            <strong>Self Hash:</strong>
                            <code>${this.escapeHtml(receipt.selfHash)}</code>
                        </div>
                        <div class="detail-field">
                            <strong>Signature:</strong>
                            <code class="sig">${this.escapeHtml(receipt.sig)}</code>
                        </div>
                    </div>
                </td>
            </tr>
        `;
    }

    toggleDetail(row) {
        const detailRow = row.nextElementSibling;
        if (detailRow && detailRow.classList.contains('detail-row')) {
            detailRow.classList.toggle('hidden');
        }
    }

    formatTs(ts) {
        const date = new Date(ts);
        return date.toLocaleString();
    }

    escapeHtml(text) {
        if (!text) return '';
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }

    showError(message) {
        console.error(message);
        if (this.tableBody) {
            this.tableBody.innerHTML = `<tr><td colspan="5" class="error">${this.escapeHtml(message)}</td></tr>`;
        }
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.receiptsViewer = new ReceiptsViewer();
});