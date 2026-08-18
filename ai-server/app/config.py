import os

# 모델은 env로 덮어쓸 수 있음. 기본값은 최신 Claude.
MODEL_VISION = os.getenv("AI_MODEL_VISION", "claude-sonnet-5")          # 근무표 사진 파싱
MODEL_FAST = os.getenv("AI_MODEL_FAST", "claude-haiku-4-5-20251001")    # 재설계/조정 (3초 안)
MAX_TOKENS = int(os.getenv("AI_MAX_TOKENS", "1024"))

# 근무표 추출은 날짜 하나당 한 칸씩 뱉으므로 출력이 길다. 실측: 31일 1행 = 1007 토큰
# (=기존 1024 상한 바로 아래), 두 달치(61일)면 약 2000 토큰이라 상한에 잘려서 shifts 가
# 통째로 사라졌다 → IMAGE_UNREADABLE → 백엔드 PARSE_FAILED. 상한은 요금이 아니라 천장이므로
# 넉넉히 잡는다(안 쓰면 안 낸다).
MAX_TOKENS_VISION = int(os.getenv("AI_MAX_TOKENS_VISION", "8192"))

# 근무표 격자는 저해상도면 행/열이 밀린다. 이보다 작으면 확대해서 보낸다.
MIN_IMAGE_WIDTH = int(os.getenv("AI_MIN_IMAGE_WIDTH", "1568"))
# 폰으로 찍은 원본(4000px)은 정확도는 그대로인데 이미지 토큰만 4배 든다.
# 실측(같은 표): 1096px=1083토큰, 1568px=2187, 1800px=2863, 2400px 이상=4717(상한).
# 긴 변을 여기로 맞춰 보낸다 — 위아래 양방향.
MAX_IMAGE_EDGE = int(os.getenv("AI_MAX_IMAGE_EDGE", "1568"))
