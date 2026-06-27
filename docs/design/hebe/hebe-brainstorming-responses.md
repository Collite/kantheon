# Hebe Brainstorming Responses

## 1. Pushbacks

### 1.3 Channels

Agreed. Let's do Console + CLI + Telegram

### 1.4 Routines vs SOPs

Agreed. Routines in v1.

### 1.5 Web Console

Agreed

### 1.6 security-tag

Agreed

### 1.7 Memory

Agreed with the suggestions

### 1.8 Persistence

Agreed, SQLite in v1

### 1.9 Extendability

Agreed with the conceptual split

### 1.10

Agreed, Brave + DuckDuckGo


## 2. Bets

Accepted all

## 3. Open Questions

3.1: no, SOPs are in v2
3.2: and also 3.6: single user, single human per instance
3.3: agreed with IDENTITY
3.4: OpenAI API; we actually have a LLM Gateway on our Platform that handles that.
3.5: sub-agents-as-tools is good
3.7: not a priority for v1


## 6. New questions

6.1: agreed with plugin-api
6.2: let's go PF4J
6.3: the three features are good enough for v1
6.4: v1 optional is good
6.5: shell tool sandbox v2
6.6: plugin hot reload: not a priority at all
6.7: plugin template repo: for v1, 2 and few others, plugins will be internal, served from a container registry (ACR)

## 7. Hot Answers
- SOPs in v2 only
- only Telegram
- signature_mode=optional default

- no v1/v2 engines coexistence, we are building a new thing
- one user one instance
- OpenAI API + BYOK


