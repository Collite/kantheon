class HebeChat {
    constructor() {
        this.sessionId = this.getOrCreateSessionId();
        this.lastEventId = 0;
        this.eventSource = null;
        this.pendingApproval = null;

        this.messagesContainer = document.getElementById('messages');
        this.messageInput = document.getElementById('messageInput');
        this.messageForm = document.getElementById('messageForm');
        this.sendButton = document.getElementById('sendButton');
        this.typingIndicator = document.getElementById('typingIndicator');
        this.approvalModal = document.getElementById('approvalModal');
        this.sessionIdDisplay = document.getElementById('sessionId');

        this.sessionIdDisplay.textContent = `Session: ${this.sessionId}`;

        this.init();
    }

    getOrCreateSessionId() {
        let sessionId = localStorage.getItem('hebe-session-id');
        if (!sessionId) {
            sessionId = crypto.randomUUID();
            localStorage.setItem('hebe-session-id', sessionId);
        }
        return sessionId;
    }

    init() {
        this.messageForm.addEventListener('submit', (e) => this.handleSubmit(e));
        this.messageInput.addEventListener('input', () => this.autoResize());
        document.getElementById('approveBtn').addEventListener('click', () => this.handleApproval(true));
        document.getElementById('denyBtn').addEventListener('click', () => this.handleApproval(false));

        this.connectSSE();
    }

    connectSSE() {
        if (this.eventSource) {
            this.eventSource.close();
        }

        const url = `/api/sessions/${this.sessionId}/events?lastEventId=${this.lastEventId}`;
        this.eventSource = new EventSource(url);

        this.eventSource.onopen = () => {
            console.log('SSE connected');
        };

        this.eventSource.onerror = (error) => {
            console.error('SSE error:', error);
            setTimeout(() => this.connectSSE(), 3000);
        };

        this.eventSource.addEventListener('text_delta', (e) => {
            this.handleTextDelta(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('done', (e) => {
            this.handleDone(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('approval_requested', (e) => {
            this.handleApprovalRequested(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('token_usage', (e) => {
            this.handleTokenUsage(JSON.parse(e.data));
        });

        this.eventSource.addEventListener('error', (e) => {
            this.handleError(JSON.parse(e.data));
        });

        this.eventSource.onmessage = (e) => {
            const data = JSON.parse(e.data);
            this.lastEventId = data.id || this.lastEventId;
        };
    }

    async handleSubmit(e) {
        e.preventDefault();
        const content = this.messageInput.value.trim();
        if (!content) return;

        this.messageInput.value = '';
        this.autoResize();
        this.addMessage('user', content);
        this.setTyping(true);

        try {
            const response = await fetch('/api/messages', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ content, sessionId: this.sessionId })
            });

            if (!response.ok) {
                const error = await response.json();
                this.addError(error.error || 'Failed to send message');
            }
        } catch (error) {
            this.addError('Network error. Please try again.');
            console.error('Send error:', error);
        }

        this.setTyping(false);
    }

    handleTextDelta(data) {
        this.setTyping(false);
        if (data.text) {
            this.appendToLastMessage(data.text);
        }
    }

    appendToLastMessage(text) {
        const messages = this.messagesContainer.querySelectorAll('.message.assistant');
        if (messages.length === 0) return;
        const lastMessage = messages[messages.length - 1];
        const contentDiv = lastMessage.querySelector('.content');
        if (contentDiv) {
            contentDiv.textContent += text;
            this.scrollToBottom();
        }
    }

    handleDone(data) {
        this.setTyping(false);
    }

    handleApprovalRequested(data) {
        this.pendingApproval = data;
        document.getElementById('approvalMessage').textContent = data.text || 'A tool execution is requested.';
        document.getElementById('approvalTool').textContent = data.tool || 'Unknown';
        this.approvalModal.classList.remove('hidden');
    }

    handleTokenUsage(data) {
        console.log('Token usage:', data);
    }

    handleError(data) {
        this.setTyping(false);
        this.addError(data.message || 'An error occurred');
    }

    async handleApproval(approved) {
        this.approvalModal.classList.add('hidden');
        if (!this.pendingApproval) return;

        const approvalId = this.pendingApproval.approvalId;

        try {
            await fetch('/api/approvals', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({ approvalId, approved })
            });
        } catch (error) {
            console.error('Approval error:', error);
            this.addError('Failed to send approval response');
        }

        this.pendingApproval = null;
    }

    addMessage(type, content, toolRequest = null) {
        const message = document.createElement('div');
        message.className = `message ${type}`;

        let html = `<div class="sender">${type === 'user' ? 'You' : 'Assistant'}</div>`;
        html += `<div class="content">${this.escapeHtml(content)}</div>`;

        if (toolRequest) {
            html += `<div class="tool-request">Tool: <span class="tool-name">${this.escapeHtml(toolRequest.tool)}</span></div>`;
        }

        message.innerHTML = html;
        this.messagesContainer.appendChild(message);
        this.scrollToBottom();
    }

    addError(message) {
        const errorDiv = document.createElement('div');
        errorDiv.className = 'error-message';
        errorDiv.textContent = message;
        this.messagesContainer.appendChild(errorDiv);
        this.scrollToBottom();
    }

    setTyping(show) {
        if (show) {
            this.typingIndicator.classList.remove('hidden');
        } else {
            this.typingIndicator.classList.add('hidden');
        }
    }

    autoResize() {
        this.messageInput.style.height = 'auto';
        this.messageInput.style.height = Math.min(this.messageInput.scrollHeight, 120) + 'px';
    }

    scrollToBottom() {
        this.messagesContainer.scrollTop = this.messagesContainer.scrollHeight;
    }

    escapeHtml(text) {
        const div = document.createElement('div');
        div.textContent = text;
        return div.innerHTML;
    }
}

document.addEventListener('DOMContentLoaded', () => {
    window.hebeChat = new HebeChat();
});