"""수집 세션 폴더를 읽는다.

## 왜 pandas를 쓰지 않는가
이 도구는 촬영 현장에서 돌아야 한다 — 세션을 찍고 그 자리에서 "이거 쓸 수 있나"를 봐야
하고, 못 쓸 세션이면 사람들이 흩어지기 전에 다시 찍어야 한다. 그 자리에서
`pip install`이 필요하면 도구가 없는 것과 같다. 표준 라이브러리만 쓴다.

## 왜 컬럼을 골라 읽는가
`frames.csv`는 161개 컬럼이고 그중 130개가 키포인트 좌표다(26점 × 원본·필터 x·y + score).
세션 18개면 2만 행이 넘는데 전부 dict로 들고 있으면 좌표가 메모리를 다 먹는다. 좌표는
특징을 다시 계산할 때만 필요하고, 지금 도구들은 이미 계산된 특징만 본다.
"""

from __future__ import annotations

import csv
import glob
import os
from dataclasses import dataclass, field

# ── 각도별 필수 특징 ────────────────────────────────────────
#
# 촬영 각도가 지원하지 않는 특징은 애초에 비어 나온다. 측면에서 무릎 좌우 정렬을 보려
# 하거나 정면에서 깊이를 보려 하면 원근 왜곡 때문에 값이 나와도 신뢰할 수 없다.
# 그래서 "비어 있으면 문제"인 목록이 각도마다 다르다.

SIDE_FEATURES = [
    "depth_ratio",
    "thigh_angle",
    "trunk_lean",
    "shin_angle",
    "hip_drop_ratio",
    "hip_travel_ratio",
]

FRONT_FEATURES = [
    "stance_width_ratio",
    "hip_shift_ratio",
    "pelvis_tilt",
    "shoulder_tilt",
    "trunk_lateral_lean",
    "knee_asymmetry",
]

# 각도에 무관하게 채워지는 특징. 세션을 섞어 볼 때 쓸 수 있는 것들이다.
COMMON_FEATURES = [
    "left_knee_flexion",
    "right_knee_flexion",
    "left_hip_flexion",
    "right_hip_flexion",
    "min_confidence",
]

FRAME_META = [
    "frame_index",
    "timestamp_ms",
    "rep_id",
    "phase",
    "phase_progress",
]

# rep 행에서 보는 값. 요약 특징과 품질 컬럼.
REP_FEATURES = [
    "duration_ms",
    "descent_duration_ms",
    "ascent_duration_ms",
    "max_knee_flexion",
    "max_hip_flexion",
    "max_ankle_dorsi",
    "max_depth_ratio",
    "min_thigh_angle",
    "max_trunk_lean",
    "max_heel_rise",
    "max_left_medial_knee",
    "max_right_medial_knee",
    "max_hip_shift",
]


def features_for(view: str) -> list[str]:
    """그 각도에서 채워져야 하는 특징."""
    if view == "FRONT":
        return FRONT_FEATURES
    return SIDE_FEATURES


@dataclass
class Session:
    path: str
    meta: dict[str, str]
    frames: list[dict[str, str]] = field(default_factory=list)
    reps: list[dict[str, str]] = field(default_factory=list)

    @property
    def name(self) -> str:
        return os.path.basename(self.path.rstrip("/\\"))

    @property
    def view(self) -> str:
        return self.meta.get("view", "")

    @property
    def person(self) -> str:
        return self.meta.get("person_id", "")

    @property
    def footwear(self) -> str:
        return self.meta.get("footwear", "")

    @property
    def label(self) -> str:
        """사람·각도·신발을 한 줄로. 조건이 섞였는지 눈에 보이게 한다."""
        return f"{self.person}/{self.view}/{self.footwear or '?'}"

    def reps_in_reference(self) -> list[dict[str, str]]:
        """기준 표본에 포함된 rep만. 이상치·품질 미달·오류 의도는 빠져 있다."""
        return [r for r in self.reps if r.get("include_in_reference") == "1"]

    def frames_of(self, rep_ids: set[str]) -> list[dict[str, str]]:
        return [f for f in self.frames if f.get("rep_id") in rep_ids]


def num(row: dict[str, str], key: str) -> float | None:
    """빈 칸을 None으로. CSV의 빈 칸은 "판정 불가"라는 뜻이므로 0으로 읽으면 안 된다."""
    value = row.get(key, "")
    if value == "" or value is None:
        return None
    try:
        return float(value)
    except ValueError:
        return None


def read_rows(path: str, columns: list[str] | None) -> list[dict[str, str]]:
    if not os.path.exists(path):
        return []
    with open(path, encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        if columns is None:
            return list(reader)
        wanted = [c for c in columns if c in (reader.fieldnames or [])]
        return [{c: row[c] for c in wanted} for row in reader]


def load(path: str, frame_columns: list[str] | None = None) -> Session | None:
    """세션 폴더 하나를 읽는다. session.csv가 없으면 세션이 아니다."""
    session_csv = os.path.join(path, "session.csv")
    if not os.path.exists(session_csv):
        return None

    meta_rows = read_rows(session_csv, None)
    if not meta_rows:
        return None
    meta = meta_rows[0]

    if frame_columns is None:
        view = meta.get("view", "")
        frame_columns = FRAME_META + COMMON_FEATURES + features_for(view)
        # 뒤꿈치는 측면 전용이지만 활성측이 어느 쪽인지에 따라 컬럼이 갈린다.
        frame_columns += ["left_heel_rise", "right_heel_rise"]

    return Session(
        path=path,
        meta=meta,
        frames=read_rows(os.path.join(path, "frames.csv"), frame_columns),
        reps=read_rows(os.path.join(path, "reps.csv"), None),
    )


def load_all(paths: list[str], frame_columns: list[str] | None = None) -> list[Session]:
    """세션 폴더들을 읽는다.

    인자로 세션 폴더를 직접 줘도 되고, 여러 세션을 담은 상위 폴더를 줘도 된다 — 기기에서
    `captures/`를 통째로 꺼내는 것이 가장 흔한 경우다.
    """
    sessions: list[Session] = []
    seen: set[str] = set()

    for raw in paths:
        for candidate in sorted(glob.glob(raw)) or [raw]:
            if not os.path.isdir(candidate):
                continue
            targets = [candidate]
            if not os.path.exists(os.path.join(candidate, "session.csv")):
                targets = sorted(
                    d for d in glob.glob(os.path.join(candidate, "*")) if os.path.isdir(d)
                )
            for target in targets:
                real = os.path.realpath(target)
                if real in seen:
                    continue
                session = load(target, frame_columns)
                if session is not None:
                    seen.add(real)
                    sessions.append(session)

    # 사람 → 각도 → 촬영 시각 순. 같은 사람의 세션이 붙어 나와야 읽기 쉽다.
    sessions.sort(key=lambda s: (s.person, s.view, s.meta.get("captured_at", "")))
    return sessions
