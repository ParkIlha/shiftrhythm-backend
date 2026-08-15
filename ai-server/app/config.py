import os

# 모델은 env로 덮어쓸 수 있음. 기본값은 최신 Claude.
MODEL_VISION = os.getenv("AI_MODEL_VISION", "claude-sonnet-5")          # 근무표 사진 파싱
MODEL_FAST = os.getenv("AI_MODEL_FAST", "claude-haiku-4-5-20251001")    # 재설계/조정 (3초 안)
MAX_TOKENS = int(os.getenv("AI_MAX_TOKENS", "1024"))
