"""세션 위생 점검 — 촬영 당일에 "이거 쓸 수 있나"를 답한다.

## 왜 필요한가
이 프로젝트에서 못 쓸 데이터를 **다 찍은 뒤에** 알아차린 일이 두 번 있었다.

1. 진행도 컬럼이 2243행 전부 빈 칸으로 나왔다 (프레임을 즉시 기록해서).
2. 하강 진행도 0~50% 구간에 표본이 하나도 없었다 (rep 창이 늦게 열려서).

두 번 다 촬영이 끝난 다음이었고, 두 번 다 재촬영이었다. 촬영은 되돌리기가 가장 비싼
작업이므로 판단을 **그 자리로** 옮겨야 한다.

## 왜 각 기준이 이 값인가
전부 실측에서 나온 숫자다. 통과선은 "정상일 때 실측값"과 "고장났을 때 실측값" 사이에
둔다 — 어느 쪽에서도 여유가 있어야 경계가 흔들리지 않는다.

사용법:
    python check_sessions.py <세션폴더 또는 captures 폴더> [...]
"""

from __future__ import annotations

import statistics as st
import sys
from collections import Counter

import console
from capture_data import Session, features_for, load_all, num

# ── 통과 기준 ───────────────────────────────────────────────

# 프레임 유실. 시계열에 구멍이 나면 진행도 재계산이 불가능해진다. 실측 정상 세션은
# 불연속 0곳이었고, 고장 세션은 19곳이었다. 타협할 값이 아니다.
MAX_INDEX_GAPS = 0

# rep 밖 프레임. 하나도 없으면 rep 단위로만 기록되던 옛 빌드다 — 하강 시작 앞뒤가
# 파일에 없다는 뜻이므로 재계산으로도 복구할 수 없다.
MIN_OUTSIDE_FRAMES = 1

# 하강 전반부(진행도 0.5 미만) 표본. 실측 고장 세션은 0개, 정상 세션은 200~238개였다.
# rep당 최소 몇 개는 나와야 한다.
MIN_EARLY_DESCENT_PER_REP = 2.0

# 하강/상승 길이 비율. 창이 늦게 열리면 하강만 짧아진다 — 실측 고장 0.42, 정상 1.18~1.26.
# 사람마다 템포가 다르니 넓게 두고, 0.42가 확실히 걸리는 선으로 잡는다.
MIN_DESCENT_RATIO = 0.6
MAX_DESCENT_RATIO = 2.5

# rep 첫 프레임 무릎 굴곡(도). 되돌리기가 선 자세까지 갔는지. 실측 고장 55.8, 정상 2.4~5.3.
MAX_FIRST_FLEXION = 15.0

# 각도별 필수 특징 채움률. 실측 고장 0%, 정상 100%. 가림이 있어도 90%는 넘어야
# rep 자동 제외 기준(유효 프레임 90%)과 앞뒤가 맞는다.
MIN_FEATURE_FILL = 0.90

# 극값 여유 = bottom_dwell_ms / max_frame_interval_ms.
# 최저점 근처에 프레임이 이만큼은 있어야 최대 굴곡·깊이를 놓치지 않는다. 실측 여유가
# 좌측면 3.4배, 우측면 5.9배였다. 3 아래로 내려간 rep은 극값을 의심해야 한다.
MIN_EXTREMUM_MARGIN = 3.0

# 그런 rep이 몇 개까지 허용되나. 몇 개는 걸러 쓰면 되지만 절반이 그러면 세션이 문제다.
MAX_LOW_MARGIN_RATIO = 0.2

# 이상치 비율. 너무 높으면 세션이 일관되지 않았다는 뜻이다(워밍업, 중간 휴식, 자세 변화).
MAX_OUTLIER_RATIO = 0.35

# 세션 메타에서 비어 있으면 안 되는 것. 나중에 조건별로 나눠 보려면 필요하다.
REQUIRED_META = ["person_id", "view", "footwear", "pose_model", "analysis_fps"]


class Result:
    def __init__(self) -> None:
        self.lines: list[tuple[bool, str]] = []

    def check(self, ok: bool, text: str) -> None:
        self.lines.append((ok, text))

    @property
    def failures(self) -> list[str]:
        return [text for ok, text in self.lines if not ok]

    @property
    def passed(self) -> bool:
        return not self.failures


def knee_flexion(frame: dict[str, str]) -> float | None:
    """활성측을 모르므로 있는 쪽을 쓴다. 둘 다 있으면 평균 — 앱과 같은 규칙이다."""
    left = num(frame, "left_knee_flexion")
    right = num(frame, "right_knee_flexion")
    if left is not None and right is not None:
        return (left + right) / 2
    return left if left is not None else right


def inspect(session: Session) -> Result:
    result = Result()
    frames = session.frames
    reps = session.reps

    if not frames or not reps:
        result.check(False, f"프레임 {len(frames)}개 / rep {len(reps)}개 — 비어 있다")
        return result

    # ── 메타 ────────────────────────────────────────────────
    blank = [k for k in REQUIRED_META if not session.meta.get(k)]
    result.check(not blank, f"세션 메타 공란: {blank or '없음'}")

    jitter = num(session.meta, "calibration_jitter")
    calib_frames = num(session.meta, "calibration_frames")
    result.check(
        calib_frames is not None and calib_frames >= 30,
        f"캘리브레이션 프레임 {calib_frames} (지터 {jitter})",
    )

    # ── 프레임 연속성 ───────────────────────────────────────
    indices = [int(float(f["frame_index"])) for f in frames]
    gaps = sum(1 for a, b in zip(indices, indices[1:]) if b - a != 1)
    result.check(gaps <= MAX_INDEX_GAPS, f"frame_index 불연속 {gaps}곳 (유실이 있으면 0이 아니다)")

    # ── rep 밖 프레임 ───────────────────────────────────────
    outside = sum(1 for f in frames if not f.get("rep_id"))
    result.check(
        outside >= MIN_OUTSIDE_FRAMES,
        f"rep 밖 프레임 {outside}개 / 전체 {len(frames)}개",
    )

    # ── 하강 전반부 표본 ────────────────────────────────────
    in_rep = [f for f in frames if f.get("rep_id")]
    early = sum(
        1
        for f in in_rep
        if f.get("phase") == "DESCENDING" and (num(f, "phase_progress") or 1.0) < 0.5
    )
    per_rep = early / len(reps)
    result.check(
        per_rep >= MIN_EARLY_DESCENT_PER_REP,
        f"하강 전반부(진행도<0.5) 프레임 {early}개 = rep당 {per_rep:.1f}개",
    )

    # ── 하강/상승 비율 ──────────────────────────────────────
    descents = [num(r, "descent_duration_ms") for r in reps]
    ascents = [num(r, "ascent_duration_ms") for r in reps]
    descents = [d for d in descents if d]
    ascents = [a for a in ascents if a]
    if descents and ascents:
        ratio = st.median(descents) / st.median(ascents)
        result.check(
            MIN_DESCENT_RATIO <= ratio <= MAX_DESCENT_RATIO,
            f"하강 {st.median(descents):.0f}ms / 상승 {st.median(ascents):.0f}ms = {ratio:.2f}",
        )
    else:
        result.check(False, "하강·상승 길이가 비어 있다")

    # ── rep 첫 프레임 굴곡 ──────────────────────────────────
    by_rep: dict[str, list[dict[str, str]]] = {}
    for f in in_rep:
        by_rep.setdefault(f["rep_id"], []).append(f)
    firsts = [knee_flexion(v[0]) for v in by_rep.values()]
    firsts = [x for x in firsts if x is not None]
    if firsts:
        median_first = st.median(firsts)
        result.check(
            median_first <= MAX_FIRST_FLEXION,
            f"rep 첫 프레임 굴곡 중앙값 {median_first:.1f}° "
            f"(범위 {min(firsts):.1f}~{max(firsts):.1f})",
        )

    # ── 각도별 특징 채움률 ──────────────────────────────────
    required = features_for(session.view)
    worst_name, worst_fill = "", 1.0
    for name in required:
        filled = sum(1 for f in in_rep if num(f, name) is not None)
        fill = filled / len(in_rep) if in_rep else 0.0
        if fill < worst_fill:
            worst_name, worst_fill = name, fill
    result.check(
        worst_fill >= MIN_FEATURE_FILL,
        f"{session.view} 필수 특징 최저 채움률 {worst_fill:.0%} ({worst_name or '-'})",
    )

    # ── 극값 여유 ───────────────────────────────────────────
    margins = []
    for r in reps:
        dwell = num(r, "bottom_dwell_ms")
        interval = num(r, "max_frame_interval_ms")
        if dwell is not None and interval and interval > 0:
            margins.append(dwell / interval)
    if margins:
        low = sum(1 for m in margins if m < MIN_EXTREMUM_MARGIN)
        result.check(
            low / len(margins) <= MAX_LOW_MARGIN_RATIO,
            f"극값 여유 중앙값 {st.median(margins):.1f}프레임, "
            f"{MIN_EXTREMUM_MARGIN}프레임 미달 {low}/{len(margins)}개",
        )
    else:
        # 옛 빌드로 찍은 세션. 판단 근거가 없다는 사실 자체를 보고한다.
        result.check(False, "bottom_dwell_ms / max_frame_interval_ms 컬럼이 없다 (옛 빌드)")

    # ── 실효 fps ────────────────────────────────────────────
    intervals = [num(r, "frame_interval_ms") for r in reps]
    intervals = [x for x in intervals if x]
    if intervals:
        first_half = intervals[: len(intervals) // 2] or intervals
        second_half = intervals[len(intervals) // 2 :] or intervals
        fps_a = 1000 / st.median(first_half)
        fps_b = 1000 / st.median(second_half)
        # 실패로 보지 않는다 — 발열은 코드로 못 고치고, 극값 여유가 실제 영향을 잡는다.
        result.check(True, f"fps 앞 절반 {fps_a:.1f} → 뒤 절반 {fps_b:.1f} (발열 저하)")

    # ── 이상치·포함 rep ─────────────────────────────────────
    outliers = sum(1 for r in reps if r.get("is_outlier") == "1")
    included = len(session.reps_in_reference())
    reasons = Counter(r.get("exclusion_reason") or "-" for r in reps)
    result.check(
        outliers / len(reps) <= MAX_OUTLIER_RATIO,
        f"이상치 {outliers}/{len(reps)}개, 기준 표본 포함 {included}개",
    )
    result.check(True, f"제외 사유: {dict(reasons)}")

    return result


def main(argv: list[str]) -> int:
    console.setup()
    if not argv:
        print(__doc__)
        return 2

    sessions = load_all(argv)
    if not sessions:
        print("세션을 찾지 못했다. session.csv가 있는 폴더나 그 상위 폴더를 지정할 것.")
        return 2

    failed = 0
    for session in sessions:
        result = inspect(session)
        mark = "PASS" if result.passed else "FAIL"
        print("=" * 78)
        print(f"[{mark}] {session.name}")
        print(f"       {session.label}  프레임 {len(session.frames)}  rep {len(session.reps)}")
        for ok, text in result.lines:
            print(f"   {'.' if ok else 'X'} {text}")
        if not result.passed:
            failed += 1

    print("=" * 78)
    print(f"세션 {len(sessions)}개 중 {failed}개 실패")

    # 조건이 섞였는지 본다. 사람과 신발이 함께 바뀌면 어느 쪽 효과인지 갈라낼 수 없다.
    combos = Counter(s.label for s in sessions)
    print("\n조건별 세션 수:")
    for label, count in sorted(combos.items()):
        flag = "" if count >= 2 else "   ← 세션 1개. 개인 내 변동을 알 수 없다"
        print(f"  {label:28s} {count}개{flag}")

    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
