import base64
from anthropic import Anthropic
from app.config import MAX_TOKENS

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
    return media, b64


if __name__ == "__main__":
    # ponytail 자체점검: media-type 스니핑
    png = base64.b64encode(b"\x89PNG\r\n\x1a\n0000").decode()
    jpg = base64.b64encode(b"\xff\xd8\xff\xe00000").decode()
    assert decode_image(png)[0] == "image/png"
    assert decode_image(jpg)[0] == "image/jpeg"
    assert decode_image("data:image/webp;base64,AAAA") == ("image/webp", "AAAA")
    print("ok")
