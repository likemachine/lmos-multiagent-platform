# Архитектура

```text
Клиент
  ↓ WebSocket
LMOS Runtime
  ↓ LLM-классификатор (llama3.2)
Выбор агента
  ↓ GraphQL WebSocket
Kotlin/ARC-агент
  ↓ Ollama или MCP
Ответ

Принцип агентов
Все генеративные агенты используют один универсальный Kotlin-runtime.
Различия между агентами задаются переменными окружения, моделью Ollama,
описанием в ресурсе  Agent  и включением в  ChannelRouting .
Роли агентов
•	 llama-agent  — общие вопросы и тексты.
•	 code-agent  — программирование, Bash, отладка.
•	 postgres-analyst  — анализ PostgreSQL через MCP.
