"""사람 내 변동과 사람 간 변동을 분해한다 — "정규화가 체형을 지웠나"의 답.

## 왜 이걸 봐야 하는가
목표는 사용자 자세를 정상 데이터와 비교해 리포트를 내는 것이다. 그런데 다른 사람의
정상 범위와 비교하면 **체형 차이가 "오류"로 보고된다.** 그래서 두 갈래가 있다.

- 모집단 범위를 만든다 → 사람이 수십 명 필요하다.
- 사용자마다 캘리브레이션해서 상대 편차로 판정한다 → 정규화가 체형을 지워야 성립한다.

이 도구는 두 번째 길이 성립하는지를 특징별로 답한다. 사람 간 변동이 사람 내 변동보다
훨씬 크면 그 특징은 체형에 오염돼 있고, 공통 임계값으로 쓸 수 없다.

## 왜 표준편차가 아니라 MAD인가
표본이 사람 몇 명·rep 수십 개 규모다. 이 크기에서 표준편차는 이상치 하나에 좌우되고,
그 이상치가 바로 우리가 찾는 "다른 사람"일 수 있다. 앱의 이상치 판정도 같은 이유로
MAD를 쓴다.

## ⚠ 측면 촬영에서 쓸 수 없는 체형 변수
`hip_width`·`shoulder_width`는 좌우 간격이라 측면에서는 카메라 축 방향이 되어 원근으로
압축된다. 실측에서 같은 사람 P001의 `hipWidth/legLength`가 정면 0.334, 측면 0.055로
**6배** 차이였다. 측면 세션끼리 비교해도 "몸이 얼마나 정확히 측면을 향했는가"를 재게
되므로 체형 변수로 쓰면 안 된다. `trunk_length/legLength`는 세로 방향이라 정면 0.781 /
측면 0.779로 강건했다.

사용법:
    python variance_split.py <세션폴더...>
"""

from __future__ import annotations

import statistics as st
import sys

import console
from capture_data import REP_FEATURES, Session, load_all, num

NORMAL_SCALE = 1.4826

# 사람 간 변동을 MAD로 재려면 최소 이만큼은 필요하다. 2~3명이면 MAD가 사실상 그 둘의
# 간격이라 "변동"이라 부를 수 없다. 미달이면 비율을 내되 경고를 붙인다.
MIN_PEOPLE_FOR_MAD = 4

# 사람마다 rep이 이만큼은 있어야 사람 내 변동을 잴 수 있다.
MIN_REPS_PER_PERSON = 8

# 이 배수를 넘으면 사람 간 차이가 사람 내 변동을 압도한다 — 공통 임계값으로 못 쓴다.
# 1.0이면 두 변동이 같다는 뜻이고, 2.0은 사람 간 차이가 두 배라는 뜻이다.
TRANSFERABLE_LIMIT = 2.0


def mad(values: list[float]) -> float:
    center = st.median(values)
    return NORMAL_SCALE * st.median([abs(v - center) for v in values])


def body_shape(sessions: list[Session]) -> None:
    """체형 변수를 사람·각도별로 늘어놓는다.

    같은 사람이 각도를 바꿨을 때 흔들리는 값은 체형 변수가 아니라 촬영 각도의 측정치다.
    그 구분을 눈으로 할 수 있게 원값을 그대로 보여준다.
    """
    print("=" * 86)
    print("체형 변수 (다리 길이로 나눠 카메라 거리 영향을 뺀 값)")
    print("=" * 86)
    print(
        f"  {'person':8s} {'view':11s} {'femur/tibia':>12s} {'trunk/leg':>10s} "
        f"{'hipW/leg':>10s} {'shoulderW/leg':>14s} {'foot/leg':>9s}"
    )
    for session in sessions:
        leg = num(session.meta, "leg_length")
        if not leg:
            continue

        def ratio(key: str) -> float:
            value = num(session.meta, key)
            return (value / leg) if value is not None else float("nan")

        ftr = num(session.meta, "femur_tibia_ratio")
        print(
            f"  {session.person:8s} {session.view:11s} {ftr if ftr else float('nan'):12.4f} "
            f"{ratio('trunk_length'):10.4f} {ratio('hip_width'):10.4f} "
            f"{ratio('shoulder_width'):14.4f} {ratio('foot_length'):9.4f}"
        )
    print(
        "\n  ⚠ 측면에서 hipW·shoulderW는 체형 변수가 아니다 — 좌우 간격이 카메라 축 방향이라\n"
        "    원근으로 압축된다. 실측 P001이 정면 0.334 / 측면 0.055로 6배 차이였다.\n"
        "    같은 사람의 정면·측면 행을 비교하면 어느 값이 각도에 강건한지 바로 보인다."
    )


def split(sessions: list[Session]) -> None:
    """rep 단위 특징의 변동을 사람 내 / 사람 간으로 나눈다.

    각도를 섞지 않는다 — 측면과 정면은 채워지는 특징 자체가 다르고, 같은 특징이라도
    원근 조건이 달라 섞으면 각도 차이가 사람 차이로 잡힌다.
    """
    views = sorted({s.view for s in sessions})
    for view in views:
        subset = [s for s in sessions if s.view == view]
        people = sorted({s.person for s in subset})

        print("\n" + "=" * 86)
        print(f"{view} — 사람 {len(people)}명 ({', '.join(people)})")
        print("=" * 86)
        if len(people) < 2:
            print("  사람이 1명이라 사람 간 변동을 낼 수 없다.")
            continue
        if len(people) < MIN_PEOPLE_FOR_MAD:
            print(
                f"  ⚠ 사람이 {len(people)}명이다. 사람 간 MAD는 사실상 그 사이 간격이므로"
                " 배수를 절대값으로 믿지 말고 순서만 볼 것."
            )

        print(
            f"  {'특징':22s} {'사람내MAD':>10s} {'사람간MAD':>10s} {'배수':>7s}  판단"
        )

        results: list[tuple[float, str]] = []
        for name in REP_FEATURES:
            # 사람마다 그 사람의 rep 값 모음
            per_person: dict[str, list[float]] = {}
            for session in subset:
                for rep in session.reps_in_reference():
                    value = num(rep, name)
                    if value is not None:
                        per_person.setdefault(session.person, []).append(value)

            usable = {p: v for p, v in per_person.items() if len(v) >= MIN_REPS_PER_PERSON}
            if len(usable) < 2:
                continue

            within_each = [mad(v) for v in usable.values()]
            within = st.median(within_each)
            centers = [st.median(v) for v in usable.values()]
            between = mad(centers)
            # 사람이 2명이면 MAD가 0이 된다(두 값의 중앙값에서 각각 같은 거리).
            # 그때는 간격의 절반을 쓴다 — 같은 스케일이고 0이 아니다.
            if between <= 1e-9 and len(centers) == 2:
                between = abs(centers[0] - centers[1]) / 2

            if within <= 1e-9:
                continue
            factor = between / within
            verdict = (
                "공통 임계값 후보"
                if factor <= TRANSFERABLE_LIMIT
                else "개인 기준선 필요"
            )
            results.append(
                (
                    factor,
                    f"  {name:22s} {within:10.3f} {between:10.3f} {factor:7.2f}x  {verdict}",
                )
            )

        if not results:
            print(
                f"  비교할 특징이 없다 — 사람마다 기준 rep이 {MIN_REPS_PER_PERSON}개 이상"
                " 필요하다."
            )
            continue

        results.sort()
        for _, line in results:
            print(line)


def coverage(sessions: list[Session]) -> None:
    """사람마다 각도별 세션이 몇 개인지. 2개 미만이면 사람 내 변동을 세션 간으로 못 나눈다."""
    print("\n" + "=" * 86)
    print("촬영 설계 점검")
    print("=" * 86)
    counts: dict[tuple[str, str], int] = {}
    for session in sessions:
        key = (session.person, session.view)
        counts[key] = counts.get(key, 0) + 1

    shortfall = 0
    for (person, view), count in sorted(counts.items()):
        flag = ""
        if count < 2:
            flag = "   ← 세션 1개. 개인 내 변동과 세션 간 변동을 구분할 수 없다"
            shortfall += 1
        print(f"  {person:8s} {view:11s} {count}개{flag}")

    if shortfall:
        print(
            f"\n  {shortfall}개 조합이 세션 1개다. 사람 수를 늘리기보다 **사람마다 세션 2개**를"
            "\n  먼저 채워야 한다 — 그러지 않으면 사람 간 차이가 '그 세션이 그랬던 것'인지"
            "\n  '그 사람이 원래 그런 것'인지 갈라낼 수 없다."
        )

    footwear = {(s.person, s.footwear) for s in sessions}
    people_with_both = {
        p for p, _ in footwear if len({f for q, f in footwear if q == p}) >= 2
    }
    if not people_with_both:
        print(
            "\n  신발을 바꿔 찍은 사람이 없다. 신발 효과를 보려면 **같은 사람이 신발만**"
            "\n  바꿔야 한다 — 사람과 함께 바뀌면 두 효과가 섞인다."
        )


def main(argv: list[str]) -> int:
    console.setup()
    if not argv:
        print(__doc__)
        return 2

    sessions = [s for s in load_all(argv) if s.reps_in_reference()]
    if not sessions:
        print("기준 rep이 있는 세션을 찾지 못했다.")
        return 2

    body_shape(sessions)
    split(sessions)
    coverage(sessions)
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
