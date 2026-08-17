import base64
import io
from PIL import Image
from anthropic import Anthropic
from app.config import MAX_TOKENS, MIN_IMAGE_WIDTH

_client = None

def _get_client():
    """지연 초기화 — 키 없이 import/자체점검 되게."""
    global _client
    if _client is None:
        _client = Anthropic()  # ANTHROPIC_API_KEY 는 환경변수에서
    return _client


def call_tool(model, system, content, tools):
    """tool use 로 구조화 출력을 강제한다. (tool_name, input_dict) 반환, 없으면 (None, None)."""
    choice = {"type": "any"} if len(tools) > 1 else {"type": "tool", "name": tools[0]["name"]}
    resp = _get_client().messages.create(
        model=model,
        max_tokens=MAX_TOKENS,
        system=system,
        messages=[{"role": "user", "content": content}],
        tools=tools,
        tool_choice=choice,
    )
    for block in resp.content:
        if block.type == "tool_use":
            return block.name, block.input
    return None, None


def decode_image(b64):
    """(media_type, base64_data) 반환. data-URL 접두사 제거 + 매직바이트로 형식 추정."""
    if b64.startswith("data:"):
        header, b64 = b64.split(",", 1)
        return (header[5:].split(";")[0] or "image/jpeg"), b64
    try:
        head = base64.b64decode(b64[:16])  # 16 base64 chars -> 12 bytes (매직바이트 충분)
    except Exception:
        return "image/jpeg", b64
    if head.startswith(b"\x89PNG"):
        media = "image/png"
    elif head.startswith(b"\xff\xd8"):
        media = "image/jpeg"
    elif head[:4] == b"RIFF":
        media = "image/webp"
    elif head.startswith(b"GIF8"):
        media = "image/gif"
    else:
        media = "image/jpeg"  # 모르면 jpeg 로 시도
    return upscale_if_small(media, b64)


def upscale_if_small(media, b64):
    """작은 근무표는 확대해서 보낸다.

    근무표는 행이 수십 개인 조밀한 격자라, 저해상도면 행/열이 밀려서 회차마다 다른 값이 나온다.
    실측(600x355, 28행): 확대 전 15일 중 11일 불안정 → 3배 확대 후 0일. 카톡으로 받은 스샷을
    올리는 경우가 흔해서 서버에서 막는다. 정보가 늘진 않지만 vision 정확도가 실제로 올라간다.
    """
    try:
        im = Image.open(io.BytesIO(base64.b64decode(b64)))
        if im.width >= MIN_IMAGE_WIDTH:
            return media, b64
        scale = MIN_IMAGE_WIDTH / im.width
        im = im.convert("RGB").resize((MIN_IMAGE_WIDTH, round(im.height * scale)), Image.LANCZOS)
        buf = io.BytesIO()
        im.save(buf, format="PNG")
        return "image/png", base64.b64encode(buf.getvalue()).decode()
    except Exception:
        return media, b64  # 확대 실패는 치명적이지 않음 — 원본으로 진행


if __name__ == "__main__":
    # ponytail 자체점검: media-type 스니핑 + 작은 이미지 확대
    png = base64.b64encode(b"\x89PNG\r\n\x1a\n0000").decode()
    jpg = base64.b64encode(b"\xff\xd8\xff\xe00000").decode()
    assert decode_image(png)[0] == "image/png"      # 열리지 않는 더미 → 원본 그대로
    assert decode_image(jpg)[0] == "image/jpeg"
    assert decode_image("data:image/webp;base64,AAAA") == ("image/webp", "AAAA")

    def png_b64(w, h):
        buf = io.BytesIO()
        Image.new("RGB", (w, h), "white").save(buf, format="PNG")
        return base64.b64encode(buf.getvalue()).decode()

    small = Image.open(io.BytesIO(base64.b64decode(decode_image(png_b64(600, 355))[1])))
    assert small.width == MIN_IMAGE_WIDTH, small.width          # 작으면 확대
    big = png_b64(MIN_IMAGE_WIDTH + 200, 100)
    assert decode_image(big)[1] == big                          # 충분히 크면 그대로
    print("ok")
