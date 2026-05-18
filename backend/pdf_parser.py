from io import BytesIO

import fitz
from PIL import Image


def extract_pages(pdf_bytes: bytes) -> list[str]:
    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    try:
        return [page.get_text() for page in doc]
    finally:
        doc.close()


def render_thumbnail(pdf_bytes: bytes, max_width: int = 480) -> bytes:
    """Render the first page of the PDF as a PNG (cover image)."""
    doc = fitz.open(stream=pdf_bytes, filetype="pdf")
    try:
        page = doc[0]
        zoom = max(1.0, max_width / page.rect.width)
        matrix = fitz.Matrix(zoom, zoom)
        pix = page.get_pixmap(matrix=matrix, alpha=False)
        return pix.tobytes("png")
    finally:
        doc.close()


def normalize_cover_image(image_bytes: bytes, max_width: int = 480) -> bytes:
    """Accept any common image format (JPEG/PNG/WebP/...) and return a
    PNG-encoded thumbnail bounded by `max_width`. Preserves aspect ratio."""
    img = Image.open(BytesIO(image_bytes))
    # Drop alpha so the dark UI doesn't show through and we get smaller files.
    if img.mode not in ("RGB", "L"):
        img = img.convert("RGB")
    if img.width > max_width:
        ratio = max_width / img.width
        img = img.resize(
            (max_width, max(1, int(img.height * ratio))),
            Image.LANCZOS,
        )
    out = BytesIO()
    img.save(out, format="PNG", optimize=True)
    return out.getvalue()
