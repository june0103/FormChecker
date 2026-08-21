"""정상 범위를 산출한다 — 이 도구의 최종 산출물.

## 무엇을 만드는가
`include_in_reference = 1`인 rep의 프레임을 **단계 × 진행도 구간**으로 나눠, 특징별
백분위수를 낸다. 임계값은 이 표에서 나온다.

## 왜 시간이 아니라 진행도로 나누는가
사람마다 스쿼트 속도가 달라 시간으로 맞추면 같은 지점이 다른 자세를 가리킨다. 진행도는
무릎 굴곡 기준이라 속도에 무관하다(문서 §15.1).

## 진행도 방향 주의
하강은 0 = 선 자세, 1 = 최저점이다. **상승은 0 = 최저점, 1 = 기립이다** — 두 구간에서
방향이 반대다. 그래서 "상승 0~20%"는 최저점 직후를 뜻한다. 이걸 뒤집어 읽으면 표의
의미가 통째로 바뀐다.

## 왜 사람별로 먼저 내는가
사람을 합친 범위는 **사람 간 변동이 범위를 지배해서** 너무 넓어지고, 넓은 범위는 오류를
잡지 못한다. 사람 2~3명은 모집단이 아니다. 합친 표도 내지만 참고용이며, 그 폭이 개인별
폭보다 얼마나 넓은지를 함께 보여준다 — 그 차이가 "정규화가 체형을 못 지웠다"의 크기다.

사용법:
    python reference_range.py <세션폴더...> [-o 출력.csv]
"""

from __future__ import annotations

import csv
import statistics as st
import sys

import console
from capture_data import (
    COMMON_FEATURES,
    REP_FEATURES,
    Session,
    features_for,
    load_all,
    num,
)

# 진행도를 5등분한다. 더 잘게 쪼개면 구간당 표본이 부족해지고(실측 rep당 하강 25프레임),
# 더 크게 묶으면 "하강 초반"과 "최저점 직전"이 한 칸에 들어간다.
BINS = 5

PERCENTILES = [5, 25, 50, 75, 95]

# 구간당 이만큼은 있어야 백분위수를 낸다. p5·p95는 양 끝이라 표본이 적으면 한 값이
# 경계를 정한다 — 그건 임계값이 아니라 우연이다.
MIN_SAMPLES = 20


def percentile(values: list[float], pct: float) -> float:
    """선형 보간 백분위수. numpy 없이 계산한다."""
    ordered = sorted(values)
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * pct / 100
    low = int(position)
    high = min(low + 1, len(ordered) - 1)
    weight = position - low
    return ordered[low] * (1 - weight) + ordered[high] * weight


def bin_of(frame: dict[str, str]) -> str | None:
    """단계 × 진행도 구간 라벨. 최저점은 쪼개지 않는다 — 애초에 한 지점이다."""
    phase = frame.get("phase")
    if phase == "BOTTOM":
        return "BOTTOM"
    if phase not in ("DESCENDING", "ASCENDING"):
        return None
    progress = num(frame, "phase_progress")
    if progress is None:
        return None
    index = min(int(progress * BINS), BINS - 1)
    low = int(index * 100 / BINS)
    high = int((index + 1) * 100 / BINS)
    prefix = "하강" if phase == "DESCENDING" else "상승"
    # CSV에 들어가는 값이므로 정렬용 공백을 넣지 않는다. 순서는 bin_order가 잡고,
    # 화면 정렬은 출력할 때 한다.
    return f"{prefix} {low}-{high}"


def bin_order(label: str) -> tuple[int, int]:
    """하강 → 최저점 → 상승 순. 동작 순서대로 읽혀야 한다."""
    if label == "BOTTOM":
        return (1, 0)
    phase, span = label.split(" ", 1)
    low = int(span.split("-")[0])
    return (0, low) if phase == "하강" else (2, low)


def frame_rows(sessions: list[Session], pooled: bool) -> list[dict[str, object]]:
    """단계 × 진행도 구간별 프레임 특징 백분위수."""
    groups: dict[tuple[str, str, str], dict[str, list[float]]] = {}

    for session in sessions:
        ids = {r["rep_id"] for r in session.reps_in_reference()}
        if not ids:
            continue
        names = features_for(session.view) + COMMON_FEATURES
        for frame in session.frames_of(ids):
            label = bin_of(frame)
            if label is None:
                continue
            person = "(합침)" if pooled else session.person
            key = (person, session.view, label)
            bucket = groups.setdefault(key, {})
            for name in names:
                value = num(frame, name)
                if value is not None:
                    bucket.setdefault(name, []).append(value)

    rows: list[dict[str, object]] = []
    for (person, view, label), features in groups.items():
        for name, values in features.items():
            if len(values) < MIN_SAMPLES:
                continue
            row: dict[str, object] = {
                "person_id": person,
                "view": view,
                "bin": label,
                "feature": name,
                "n": len(values),
            }
            for pct in PERCENTILES:
                row[f"p{pct}"] = round(percentile(values, pct), 4)
            rows.append(row)

    rows.sort(key=lambda r: (r["person_id"], r["view"], bin_order(str(r["bin"])), r["feature"]))
    return rows


def rep_rows(sessions: list[Session], pooled: bool) -> list[dict[str, object]]:
    """rep 단위 요약 특징 백분위수. 템포·최대 깊이 같은 rep 전체 값이다."""
    groups: dict[tuple[str, str], dict[str, list[float]]] = {}
    for session in sessions:
        person = "(합침)" if pooled else session.person
        bucket = groups.setdefault((person, session.view), {})
        for rep in session.reps_in_reference():
            for name in REP_FEATURES:
                value = num(rep, name)
                if value is not None:
                    bucket.setdefault(name, []).append(value)

    rows: list[dict[str, object]] = []
    for (person, view), features in groups.items():
        for name, values in features.items():
            # rep은 세션당 10~20개라 프레임 기준(20)을 그대로 쓸 수 없다. 8은 이상치
            # 판정의 MIN_SAMPLES와 같은 값이다 — 중앙값이 의미를 갖는 최소선.
            if len(values) < 8:
                continue
            row: dict[str, object] = {
                "person_id": person,
                "view": view,
                "bin": "rep",
                "feature": name,
                "n": len(values),
            }
            for pct in PERCENTILES:
                row[f"p{pct}"] = round(percentile(values, pct), 4)
            rows.append(row)
    rows.sort(key=lambda r: (r["person_id"], r["view"], r["feature"]))
    return rows


def spread_comparison(per_person: list[dict[str, object]], pooled: list[dict[str, object]]) -> None:
    """개인별 폭과 합친 폭을 비교한다.

    합친 폭이 훨씬 넓으면 그 특징은 **사람마다 다른 값**이라는 뜻이다. 공통 임계값으로
    쓸 수 없고 개인 기준선이 필요하다. 정규화가 잘 된 특징은 두 폭이 비슷해야 한다.
    """
    def width(row: dict[str, object]) -> float:
        return float(row["p95"]) - float(row["p5"])  # type: ignore[arg-type]

    individual: dict[tuple[str, str, str], list[float]] = {}
    for row in per_person:
        key = (str(row["view"]), str(row["bin"]), str(row["feature"]))
        individual.setdefault(key, []).append(width(row))

    print("\n" + "=" * 78)
    print("개인별 폭 대비 합친 폭 (p95-p5). 배수가 크면 사람마다 다른 값이다.")
    print("=" * 78)
    print(f"  {'view':11s} {'구간':11s} {'특징':22s} {'개인':>9s} {'합침':>9s} {'배수':>6s}")

    comparisons: list[tuple[float, str]] = []
    skipped_single = 0
    for row in pooled:
        key = (str(row["view"]), str(row["bin"]), str(row["feature"]))
        widths = individual.get(key)
        # 그 (각도, 구간, 특징)에 사람이 한 명뿐이면 합친 값이 개인 값과 같아서 배수가
        # 항상 1.00이 된다. 결과처럼 보이지만 아무 뜻도 없다 — 같은 각도를 두 사람
        # 이상이 찍어야 비교가 성립한다.
        if not widths:
            continue
        if len(widths) < 2:
            skipped_single += 1
            continue
        person_width = st.median(widths)
        pooled_width = width(row)
        if person_width <= 1e-9:
            continue
        factor = pooled_width / person_width
        line = (
            f"  {row['view']:11s} {str(row['bin']):11s} {str(row['feature']):22s} "
            f"{person_width:9.3f} {pooled_width:9.3f} {factor:6.2f}x"
        )
        comparisons.append((factor, line))

    if not comparisons:
        print(
            f"  비교할 것이 없다. 같은 각도를 두 사람 이상이 찍어야 한다"
            f" (사람 1명뿐인 조합 {skipped_single}개를 건너뛰었다)."
        )
        return

    if skipped_single:
        print(f"  (사람 1명뿐인 조합 {skipped_single}개는 비교에서 뺐다)")
    comparisons.sort(reverse=True)
    print("  ── 배수가 큰 것 (개인 기준선이 필요한 특징) ──")
    for _, line in comparisons[:10]:
        print(line)
    print("  ── 배수가 작은 것 (공통 임계값 후보) ──")
    for _, line in comparisons[-5:]:
        print(line)


def main(argv: list[str]) -> int:
    console.setup()
    if not argv:
        print(__doc__)
        return 2

    out_path = "reference_range.csv"
    paths = []
    index = 0
    while index < len(argv):
        if argv[index] in ("-o", "--out") and index + 1 < len(argv):
            out_path = argv[index + 1]
            index += 2
            continue
        paths.append(argv[index])
        index += 1

    sessions = load_all(paths)
    if not sessions:
        print("세션을 찾지 못했다.")
        return 2

    usable = [s for s in sessions if s.reps_in_reference()]
    print(f"세션 {len(sessions)}개 중 기준 rep이 있는 것 {len(usable)}개")
    for session in usable:
        print(f"  {session.label:28s} 포함 rep {len(session.reps_in_reference()):3d}개")
    if not usable:
        print("기준 표본에 포함된 rep이 없다. include_in_reference를 확인할 것.")
        return 2

    people = sorted({s.person for s in usable})
    if len(people) < 2:
        print(f"\n주의: 사람이 {len(people)}명이다. 이 표는 개인 기준선이며 모집단 범위가 아니다.")

    per_person = frame_rows(usable, pooled=False) + rep_rows(usable, pooled=False)
    pooled = frame_rows(usable, pooled=True) + rep_rows(usable, pooled=True)
    rows = per_person + pooled

    fields = ["person_id", "view", "bin", "feature", "n"] + [f"p{p}" for p in PERCENTILES]
    with open(out_path, "w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)
    print(f"\n{len(rows)}행을 {out_path}에 썼다 (개인별 {len(per_person)} + 합침 {len(pooled)})")

    if len(people) >= 2:
        spread_comparison(per_person, pooled)

    print("\n" + "=" * 78)
    print("진행도 방향 주의: 하강은 0=선 자세, 상승은 0=최저점이다.")
    print(f"구간당 표본 {MIN_SAMPLES}개 미만은 뺐다 — p5·p95가 한 값에 좌우되면 우연이다.")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
