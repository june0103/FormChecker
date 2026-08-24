# -*- coding: utf-8 -*-
"""임계값 0이 쓸 만한가 — 프로덕션 게이트로 프레임 단위 재현.

앞선 분석은 `phase == BOTTOM` 프레임만 봤지만, 실제 판정 게이트는
`shinDepthRatio >= kneeToeMinDepth(-0.8)`이고 하강·상승의 상당 부분이 여기 들어온다.
그리고 rep 경고는 **한 프레임이라도 넘으면** 난다.
"""
import csv, glob, os, statistics

ROOT = r"C:\Users\user\Desktop\측정데이터"
MIN_CONF = 0.3
GATE_DEPTH = -0.8
MIN_FOOT_TIBIA = 0.45


def rows(p):
    with open(p, encoding="utf-8") as f:
        yield from csv.DictReader(f)


def num(r, k):
    try:
        return float(r.get(k, ""))
    except (TypeError, ValueError):
        return None


def analyse(d):
    s = next(rows(os.path.join(d, "session.csv")))
    if not s["view"].startswith("SIDE"):
        return None
    aspect = float(s["frame_aspect_ratio"])
    L = s["view"].endswith("LEFT")
    idx = dict(knee=13 if L else 14, ankle=15 if L else 16,
               heel=24 if L else 25, bt=20 if L else 21, st=22 if L else 23,
               hip=11 if L else 12)
    ohip = 12 if L else 11
    oknee, oankle = (14, 16) if L else (13, 15)

    def pt(r, i):
        c = num(r, f"kp{i}_score")
        if c is None or c < MIN_CONF:
            return None
        x, y = num(r, f"kp{i}_x"), num(r, f"kp{i}_y")
        return None if x is None or y is None else (x * aspect, y)

    # 기준선의 발/정강이 — 게이트가 보는 값. READY 프레임에서 잰다.
    ready = []
    per_rep = {}
    for r in rows(os.path.join(d, "frames.csv")):
        knee, ankle, heel = pt(r, idx["knee"]), pt(r, idx["ankle"]), pt(r, idx["heel"])
        toes = [p for p in (pt(r, idx["bt"]), pt(r, idx["st"])) if p]
        hip, hip2 = pt(r, idx["hip"]), pt(r, ohip)
        k2, a2 = pt(r, oknee), pt(r, oankle)
        if not (knee and ankle and heel and toes and hip and hip2 and k2 and a2):
            continue
        toe_x = sum(p[0] for p in toes) / len(toes)
        foot = abs(toe_x - heel[0])
        tibia = ((knee[0] - ankle[0]) ** 2 + (knee[1] - ankle[1]) ** 2) ** 0.5
        if foot <= 1e-6 or tibia <= 1e-6:
            continue

        if r.get("phase") == "READY":
            ready.append(foot / tibia)

        rep = r.get("rep_id") or ""
        if not rep:
            continue
        # 프로덕션 깊이 게이트: shinDepthRatio = (hipCenterY - kneeY) / (ankleY - kneeY)
        hip_y = (hip[1] + hip2[1]) / 2
        knee_y = (knee[1] + k2[1]) / 2
        ankle_y = (ankle[1] + a2[1]) / 2
        shin = ankle_y - knee_y
        if shin <= 1e-6:
            continue
        if (hip_y - knee_y) / shin < GATE_DEPTH:
            continue

        forward = 1.0 if toe_x > heel[0] else -1.0
        per_rep.setdefault(rep, []).append((knee[0] - toe_x) * forward / foot)

    ft = statistics.median(ready) if ready else float("nan")
    return dict(person=s["person_id"], foot_tibia=ft, reps=per_rep,
                gated_in=ft >= MIN_FOOT_TIBIA)


def main():
    ss = [a for a in (analyse(d) for d in sorted(glob.glob(os.path.join(ROOT, "capture_*")))) if a]
    T = [0.0, 0.025, 0.05, 0.075, 0.10, 0.125]

    for tag, keep in (("게이트 통과", True), ("게이트 탈락", False)):
        sel = [s for s in ss if s["gated_in"] == keep]
        if not sel:
            continue
        print("=== %s (%d세션) ===" % (tag, len(sel)))
        frames = [v for s in sel for fr in s["reps"].values() for v in fr]
        reps = [fr for s in sel for fr in s["reps"].values()]
        print("  판정 프레임 %d개 · rep %d개" % (len(frames), len(reps)))
        f = sorted(frames)
        for q in (0.5, 0.9, 0.95, 0.99):
            print("    p%-3d %+.3f" % (q * 100, f[min(len(f) - 1, int(q * len(f)))]))
        print("    최대 %+.3f" % f[-1])
        print("  임계값   프레임 초과      rep 경고(한 프레임이라도)   rep 경고(중앙값)")
        for t in T:
            fp = sum(1 for v in frames if v > t)
            ra = sum(1 for fr in reps if any(v > t for v in fr))
            rm = sum(1 for fr in reps if statistics.median(fr) > t)
            print("  %+.3f   %5d/%-5d %4.0f%%   %3d/%-3d %4.0f%%              %3d/%-3d %4.0f%%"
                  % (t, fp, len(frames), 100 * fp / len(frames),
                     ra, len(reps), 100 * ra / len(reps),
                     rm, len(reps), 100 * rm / len(reps)))
        print()

    print("=== 세션별 발/정강이 (게이트 0.45) ===")
    for s in ss:
        print("  %-5s %.3f  %s" % (s["person"], s["foot_tibia"],
                                   "통과" if s["gated_in"] else "탈락"))


if __name__ == "__main__":
    main()
