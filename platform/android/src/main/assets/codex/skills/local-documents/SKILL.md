---
name: local-documents
description: Read, inspect, OCR, create, and edit files in the selected Android workspace with the bundled command-line tools.
---

# Local documents

The shell starts in the user-selected shared-storage workspace. Use ordinary
shell commands for ordinary files; do not invent Android document tools.

- Use `mutool draw -F txt -o - FILE.pdf` for PDF text.
- If a PDF is scanned, render bounded pages to PNM with `mutool draw`, then run
  `tesseract PAGE.pnm stdout -l eng`. This compact build accepts BMP/PNM input;
  use `mutool draw` as the image decoder when needed.
- Use `officecli view FILE text` for DOCX, XLSX, or PPTX content. Use
  `officecli dump`, `get`, `query`, `set`, `add`, `remove`, and `batch` for
  structured Office work. Run `officecli --help` or command-specific help when
  the exact syntax matters.
- Write results directly in the workspace. Existing destinations may be
  overwritten when the user's request calls for it.
- Prefer bounded extraction: inspect metadata first, select relevant pages or
  sheets, and avoid dumping an entire large document into the conversation.

`TESSDATA_PREFIX` and temporary storage are already configured by Codex Mobile.
