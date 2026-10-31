# Развёртывание

## Требования

- Ubuntu-сервер
- K3s
- LMOS Operator
- LMOS Runtime
- Ollama
- JDK и Gradle для сборки agent-runtime

## Порядок

1. Развернуть Ollama и загрузить модели.
2. Развернуть LMOS Operator и Runtime.
3. Собрать `agent-runtime`.
4. Запустить экземпляры агентов с разными переменными окружения.
5. Применить Kubernetes Service и ресурс `Agent`.
6. Проверить `ChannelRouting`.
7. Отправить контрольные запросы через Runtime.

## Безопасность

Не добавляй в Git реальные `.env`, пароли, токены и ключи.
Используй `.env.example` и секреты Kubernetes.
