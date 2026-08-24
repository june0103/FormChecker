# -*- coding: utf-8 -*-
"""측면 — 무릎이 발끝을 넘는 정도.

    전방부호 = sign(toe.x - heel.x)          발 자신의 방향. 좌/우 촬영을 몰라도 된다.
    돌출     = (knee.x - toe.x) * 전방부호   양수 = 무릎이 발끝보다 앞

분모 후보 세 개를 나란히 재서 **사람 간 변동 / 사람 내 변동**을 비교한다. 이 프로젝트가
정규화 실패를 반복해서 겪었으므로(기술선택_기록 46·47·48), 그 비율이 낮은 것만 쓸 수 있다.
"""
import csv, glob, os, statistics

ROOT = r"C:\Users\user\Desktop\측정데이터"
MIN_CONF = 0.3
KP = dict(LK=13, RK=14, LA=15, RA=16, LBT=20, RBT=21, LST=22, RST=23, LHE=24, RHE=25,
          LH=11, RH=12)


def rows(path):
    with open(path, encoding="utf-8") as f:
        yield from csv.DictReader(f)


def num(r, k):
    v = r.get(k, "")
    try:
        return float(v)
    except (TypeError, ValueError):
        return None


def analyse(d):
    s = next(rows(os.path.join(d, "session.csv")))
    if not s["view"].startswith("SIDE"):
        return None
    aspect = float(s["frame_aspect_ratio"])
    side = "L" if s["view"].endswith("LEFT") else "R"

    def pt(r, idx):
        c = num(r, f"kp{idx}_score")
        if c is None or c < MIN_CONF:
            return None
        x, y = num(r, f"kp{idx}_x"), num(r, f"kp{idx}_y")
        if x is None or y is None:
            return None
        return (x * aspect, y)

    k_i = KP["LK"] if side == "L" else KP["RK"]
    a_i = KP["LA"] if side == "L" else KP["RA"]
    h_i = KP["LHE"] if side == "L" else KP["RHE"]
    bt_i = KP["LBT"] if side == "L" else KP["RBT"]
    st_i = KP["LST"] if side == "L" else KP["RST"]
    hip_i = KP["LH"] if side == "L" else KP["RH"]

    per_rep = {}
    for r in rows(os.path.join(d, "frames.csv")):
        rep = r.get("rep_id") or ""
        if not rep or r.get("phase") != "BOTTOM":
            continue
        knee, ankle, heel = pt(r, k_i), pt(r, a_i), pt(r, h_i)
        hip = pt(r, hip_i)
        toes = [p for p in (pt(r, bt_i), pt(r, st_i)) if p]
        if not (knee and ankle and heel and hip and toes):
            continue
        toe_x = sum(p[0] for p in toes) / len(toes)

        forward = 1.0 if toe_x > heel[0] else -1.0
        overhang = (knee[0] - toe_x) * forward

        foot = abs(toe_x - heel[0])
        tibia = ((knee[0] - ankle[0]) ** 2 + (knee[1] - ankle[1]) ** 2) ** 0.5
        femur = ((hip[0] - knee[0]) ** 2 + (hip[1] - knee[1]) ** 2) ** 0.5
        if min(foot, tibia, femur) <= 1e-6:
            continue
        per_rep.setdefault(rep, []).append(dict(
            raw=overhang, foot=overhang / foot, tibia=overhang / tibia,
            femur=overhang / femur, foot_len=foot, foot_tibia=foot / tibia))
    return dict(person=s["person_id"], view=s["view"], reps=per_rep)


def main():
    sessions = [a for a in (analyse(d) for d in sorted(glob.glob(os.path.join(ROOT, "capture_*")))) if a]
    print("측면 세션 %d개\n" % len(sessions))

    # rep마다 최저점 프레임의 중앙값 하나로 줄인다.
    keys = ("raw", "foot", "tibia", "femur")
    print("=== rep 최저점 돌출 (사람별 중앙값) ===")
    print("사람   view        rep  " + "".join("%10s" % k for k in keys) + "   발/정강이")
    per_person = {k: [] for k in keys}
    within = {k: [] for k in keys}
    for s in sessions:
        med = {}
        for k in keys:
            vals = [statistics.median([f[k] for f in fr]) for fr in s["reps"].values()]
            med[k] = statistics.median(vals)
            per_person[k].append(med[k])
            if len(vals) > 1:
                within[k].append(statistics.median([abs(v - med[k]) for v in vals]))
        ft = statistics.median([f["foot_tibia"] for fr in s["reps"].values() for f in fr])
        print("%-6s %-11s %3d  %8.3f %9.3f %9.3f %9.3f     %.3f"
              % (s["person"], s["view"], len(s["reps"]),
                 med["raw"], med["foot"], med["tibia"], med["femur"], ft))

    print("\n=== 정규화 비교 (사람 간 MAD / 사람 내 MAD) — 낮을수록 좋다 ===")
    for k in keys:
        b = statistics.median([abs(v - statistics.median(per_person[k])) for v in per_person[k]])
        w = statistics.median(within[k]) if within[k] else float("nan")
        ratio = b / w if w and w == w and w > 0 else float("nan")
        print("  %-6s 사람간 MAD %.4f · 사람내 MAD %.4f → %.2f배" % (k, b, w, ratio))

    print("\n=== 전체 rep 분포 (발 길이 기준) ===")
    allv = sorted(statistics.median([f["foot"] for f in fr])
                  for s in sessions for fr in s["reps"].values())
    for q in (0.05, 0.5, 0.95):
        print("  p%-3d %+.3f" % (q * 100, allv[min(len(allv) - 1, int(q * len(allv)))]))
    print("  최소 %+.3f / 최대 %+.3f / rep %d개" % (allv[0], allv[-1], len(allv)))
    over = sum(1 for v in allv if v > 0)
    print("  발끝을 넘은 rep: %d/%d (%.0f%%)" % (over, len(allv), 100 * over / len(allv)))


if __name__ == "__main__":
    main()
