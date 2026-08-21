"""Windows 콘솔에서 한글이 깨지는 것을 막는다.

기본 코드페이지가 cp949라 표에 쓰는 기호(→, ←, ×)와 한글이 `UnicodeEncodeError`로
죽거나 깨져 나온다. 촬영 현장에서 출력이 안 읽히면 도구가 없는 것과 같다.
"""

from __future__ import annotations

import sys


def setup() -> None:
    for stream in (sys.stdout, sys.stderr):
        reconfigure = getattr(stream, "reconfigure", None)
        if reconfigure is not None:
            try:
                reconfigure(encoding="utf-8")
            except (ValueError, OSError):
                # 파이프로 넘길 때 실패할 수 있다. 그 경우는 그대로 둔다.
                pass
