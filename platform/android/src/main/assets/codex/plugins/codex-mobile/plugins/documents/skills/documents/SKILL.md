---
name: documents
description: Use Codex Mobile's typed document tools to read, render, OCR, create, and edit files in the selected Android workspace.
---

# Documents

Use `documents_read` for bounded semantic extraction from PDF, image, DOCX,
XLSX, and PPTX files. Choose `native` for embedded text, `ocr` for scanned
content, or `auto` to prefer native extraction and fall back to English OCR.

Use `documents_view_pages` only when the user needs to see specific pages.
Page counts, output size, and image resolution are bounded.

Use `documents_edit` for its closed operation set. Existing files require
`overwrite=true` and the current SHA-256. Multiple operations are one staged,
validated transaction. There is no command, argv, or arbitrary-properties
escape hatch.
