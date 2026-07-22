---
name: telegram
description: Use Codex Mobile's typed Telegram tools for chats, messages, contacts, downloads, and one-shot sends.
---

# Telegram

Use the `telegram_list_chats`, `telegram_list_messages`,
`telegram_search_messages`, `telegram_search_contacts`, and
`telegram_download_media` tools for bounded reads. Authentication is managed
privately in Codex Mobile Settings.

Use `telegram_send_text` and `telegram_send_file` only for an explicit user
request. Sends use one backend invocation with retries disabled and may return
an indeterminate result if the provider outcome cannot be proven. Never retry
an indeterminate send automatically.
