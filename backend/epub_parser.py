"""EPUB → unified book representation.

EPUB doesn't have fixed-size pages, so we split each XHTML document by
paragraph and accumulate into ~1500-character chunks. Each chunk becomes
a "page" for the rest of the pipeline (mood analysis, TOC, audio cache).
"""

from io import BytesIO

import ebooklib
from bs4 import BeautifulSoup
from ebooklib import epub

CHARS_PER_PAGE = 1500


def _clean_text(html: bytes | str) -> str:
    soup = BeautifulSoup(html, "html.parser")
    # Drop nav, header, style, script entirely.
    for tag in soup(["script", "style", "nav", "header", "footer"]):
        tag.decompose()
    text = soup.get_text("\n", strip=True)
    # Collapse runs of blank lines.
    lines = [ln.strip() for ln in text.splitlines() if ln.strip()]
    return "\n".join(lines)


def extract_epub(epub_bytes: bytes) -> dict:
    """Return {'title': str, 'pages': list[str], 'toc': list[dict],
                'cover_bytes': bytes | None}."""
    book = epub.read_epub(BytesIO(epub_bytes))

    title = ""
    title_md = book.get_metadata("DC", "title")
    if title_md:
        title = str(title_md[0][0])

    # 1. Collect ordered chapter texts from the spine.
    spine_items: list[tuple[str, str]] = []  # (chapter_title_hint, text)
    for spine_entry in book.spine:
        idref = spine_entry[0] if isinstance(spine_entry, tuple) else spine_entry
        item = book.get_item_with_id(idref)
        if item is None or item.get_type() != ebooklib.ITEM_DOCUMENT:
            continue
        text = _clean_text(item.get_content())
        if text:
            spine_items.append((item.get_name(), text))

    # 2. Pack into ~CHARS_PER_PAGE pages. Track which "page" each chapter starts on.
    pages: list[str] = []
    chapter_starts: list[tuple[int, str]] = []  # (1-indexed page, chapter name)
    buffer = ""
    chapter_first_page = 1

    def flush():
        nonlocal buffer
        if buffer.strip():
            pages.append(buffer.strip())
            buffer = ""

    for chapter_name, body in spine_items:
        flush()  # new chapter — start on a fresh page
        chapter_first_page = len(pages) + 1
        chapter_starts.append((chapter_first_page, chapter_name))
        for paragraph in body.split("\n"):
            if not paragraph:
                continue
            sep = "\n\n" if buffer else ""
            if len(buffer) + len(sep) + len(paragraph) > CHARS_PER_PAGE and buffer:
                flush()
                buffer = paragraph
            else:
                buffer = buffer + sep + paragraph
    flush()

    # 3. Resolve EPUB's TOC entries to page numbers via href → chapter mapping.
    chapter_map = {name: page for page, name in chapter_starts}
    toc: list[dict] = []

    def walk(items, level: int = 1):
        for it in items:
            if isinstance(it, (list, tuple)):
                # (section, children) — recurse children
                if len(it) == 2 and hasattr(it[0], "title"):
                    section, children = it
                    page = _find_page(section, chapter_map)
                    if page:
                        toc.append({"level": level, "title": str(section.title).strip(), "page": page})
                    walk(children, level + 1)
                else:
                    walk(it, level)
            else:
                page = _find_page(it, chapter_map)
                if page and hasattr(it, "title"):
                    toc.append({"level": level, "title": str(it.title).strip(), "page": page})

    walk(book.toc)
    # If EPUB had no toc, fall back to spine chapter names.
    if not toc and chapter_starts:
        toc = [
            {"level": 1, "title": (name.rsplit(".", 1)[0] or f"섹션 {idx+1}").strip(), "page": page}
            for idx, (page, name) in enumerate(chapter_starts)
        ]

    # Deduplicate and sort.
    seen: set[int] = set()
    toc_clean: list[dict] = []
    for entry in sorted(toc, key=lambda x: x["page"]):
        if entry["page"] in seen:
            continue
        seen.add(entry["page"])
        toc_clean.append(entry)

    # 4. Cover image.
    cover_bytes: bytes | None = None
    for item in book.get_items_of_type(ebooklib.ITEM_COVER):
        cover_bytes = item.get_content()
        if cover_bytes:
            break
    if cover_bytes is None:
        for item in book.get_items_of_type(ebooklib.ITEM_IMAGE):
            if "cover" in item.get_name().lower():
                cover_bytes = item.get_content()
                break

    return {
        "title": title,
        "pages": pages,
        "toc": toc_clean,
        "cover_bytes": cover_bytes,
    }


def _find_page(item, chapter_map: dict[str, int]) -> int | None:
    href = getattr(item, "href", "") or ""
    # Drop fragment id.
    if "#" in href:
        href = href.split("#", 1)[0]
    if not href:
        return None
    # chapter_map keys are item.get_name() which is the relative path.
    for name, page in chapter_map.items():
        if name.endswith(href) or href.endswith(name):
            return page
    return None
