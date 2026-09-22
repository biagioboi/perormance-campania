import base64

import fitz
from fastapi import FastAPI, File, UploadFile

app = FastAPI(title="pdf-extractor-service")

# Private-Use-Area glyph codes some Word/LibreOffice exports use for bullet
# fonts (Symbol/Wingdings). PyMuPDF extracts them literally as these
# codepoints, which are meaningless Unicode noise to an LLM and break list
# segmentation. Map the common ones back to a plain "- " marker.
_BULLET_GLYPHS = {
    "": "-",
    "": "-",
    "": "-",
    "": "-",
    "": "-",
}


def _normalize_bullets(text: str) -> str:
    for glyph, replacement in _BULLET_GLYPHS.items():
        text = text.replace(glyph, replacement)
    return text


@app.get("/health")
def health() -> dict:
    return {"status": "ok"}


@app.post("/extract-pages")
async def extract_pages(file: UploadFile = File(...)) -> dict:
    data = await file.read()
    document = fitz.open(stream=data, filetype="pdf")
    try:
        pages = []
        for index, page in enumerate(document):
            text = _normalize_bullets(page.get_text("text")).strip()
            pixmap = page.get_pixmap(dpi=150)
            image_base64 = base64.b64encode(pixmap.tobytes("png")).decode("ascii")
            pages.append(
                {
                    "page_number": index + 1,
                    "text": text,
                    "image_base64": image_base64,
                }
            )
        return {"pages": pages}
    finally:
        document.close()


@app.post("/extract-pages-text")
async def extract_pages_text(file: UploadFile = File(...)) -> dict:
    """Text-only extraction with reading-order sorting, no image rendering.

    Intended for large, form-like documents (many pages) where label/value
    pairs must stay in visual reading order; plain (unsorted) extraction can
    interleave them out of order for some PDF table layouts.
    """
    data = await file.read()
    document = fitz.open(stream=data, filetype="pdf")
    try:
        pages = []
        for index, page in enumerate(document):
            text = _normalize_bullets(page.get_text("text", sort=True)).strip()
            pages.append({"page_number": index + 1, "text": text})
        return {"pages": pages}
    finally:
        document.close()
