#!/usr/bin/env python3
"""The hot loops of jni/filmcore.c, counted in the camera compiler's own output (tools/asm-budget.sh runs it).

    python3 tools/asm_budget.py jni/filmcore.c filmcore.s [--show NAME ...] [--mark NAME=TEXT ...] [--funcs f1 f2 ...]

A loop is marked by a comment on a line of its body, '/* hot: NAME per=WHAT max=N sp=S */' (per, max and sp
optional). gcc's line notes (-g, which ndk-build always passes and which never changes the code) say which
instructions came from that line, wherever they were inlined. The loop holding them is found in the function's
flow graph (the innermost natural loop), and each trip through it is walked from its head back to it:

    NAME  where  shortest / longest trip in instructions, stack references on the longest, the loop's size

A conditional ARM instruction counts as one (it takes its slot whether it runs or not); a call counts as one
(what it costs isn't seen here). --mark NAME=TEXT marks the first line holding TEXT without a comment
there; --funcs reports every innermost loop of those functions; --show NAME prints that loop's instructions. Exit 1 when a loop's longest trip is over max, or its stack references over sp.
"""
import re
import sys

CONDS = 'eq|ne|cs|hs|cc|lo|mi|pl|vs|vc|hi|ls|ge|lt|gt|le|al'
BRANCH = re.compile(r'^b(%s)?(\.w)?$' % CONDS)
MARK = re.compile(r'/\*\s*hot:\s*([A-Za-z0-9_-]+)([^*]*)\*/')


def marks(src, extra):
    """Each marked line: (name, line, options). extra: name=text pairs, marking the first line holding text."""
    out = []
    lines = open(src, encoding='utf-8', errors='replace').read().split('\n')
    for n, line in enumerate(lines, 1):
        m = MARK.search(line)
        if m:
            opts = dict(re.findall(r'(\w+)=(\S+)', m.group(2)))
            out.append((m.group(1), n, opts))
    for e in extra:
        name, text = e.split('=', 1)
        n = next((i for i, line in enumerate(lines, 1) if text in line), None)
        if n is None:
            sys.exit('no line holds %r' % text)
        out.append((name, n, {}))
    return out


def functions(asm, src_name):
    """{name: [(kind, text, line)]}: each function's labels and instructions, with the source line it came from."""
    funcs, cur, body, fileno, line = {}, None, None, None, 0
    for raw in open(asm, encoding='utf-8', errors='replace'):
        s = raw.split('@', 1)[0].rstrip()
        st = s.strip()
        if not st:
            continue
        m = re.match(r'\.file\s+(\d+)\s+"([^"]+)"', st)
        if m:
            if m.group(2).endswith(src_name):
                fileno = m.group(1)
            continue
        m = re.match(r'\.loc\s+(\d+)\s+(\d+)', st)
        if m:
            line = int(m.group(2)) if m.group(1) == fileno else -1
            continue
        m = re.match(r'^([A-Za-z_.$][\w.$]*):$', st)
        if m and not s.startswith('\t'):
            name = m.group(1)
            if not name.startswith('.'):
                cur, body = name, []
                funcs[cur] = body
                continue
            if body is not None:
                body.append(('label', name, line))
            continue
        if st.startswith('.'):
            if st.startswith('.size') and cur and st.split()[1].rstrip(',') == cur:
                cur, body = None, None
            continue
        if body is not None and s.startswith('\t'):
            body.append(('insn', st, line))
    return funcs


def is_return(mn, ops):
    if mn.startswith('bx') and ops.strip() == 'lr':
        return True
    if (mn.startswith('pop') or mn.startswith('ldm')) and 'pc' in ops:
        return True
    if mn.startswith('ldr') and ops.split(',')[0].strip() == 'pc':
        return True
    return False


def conditional(mn):
    """A return carrying a condition (divided or unified syntax): bxne, popeq, ldmeqfd, ldrne."""
    for base in ('bx', 'pop', 'ldm', 'ldr'):
        if mn.startswith(base):
            c = mn[len(base):len(base) + 2]
            return c != 'al' and c in CONDS.split('|')
    return False


def blocks_of(body):
    """Basic blocks: [(labels, insns, succ_labels, falls_through)] and the label -> block index map."""
    blocks, cur_labels, cur = [], [], []

    def close(succ, falls):
        blocks.append((cur_labels[:], cur[:], succ, falls))
        cur_labels.clear()
        cur.clear()

    for kind, text, line in body:
        if kind == 'label':
            if cur:
                close([], True)
            cur_labels.append(text)
            continue
        parts = text.split(None, 1)
        mn, ops = parts[0], parts[1] if len(parts) > 1 else ''
        cur.append((text, line))
        if BRANCH.match(mn):
            target = ops.strip()
            cond = mn not in ('b', 'b.w')
            close([target], cond)
        elif is_return(mn, ops):
            close([], conditional(mn))
        elif mn.startswith('add') and ops.split(',')[0].strip() == 'pc':   # a jump table: its b's follow
            close([], True)
    if cur or cur_labels:
        close([], False)
    where = {}
    for i, (labels, _, _, _) in enumerate(blocks):
        for l in labels:
            where[l] = i
    succ = []
    for i, (_, insns, targets, falls) in enumerate(blocks):
        s = [where[t] for t in targets if t in where]
        if falls and i + 1 < len(blocks):
            s.append(i + 1)
        succ.append(s)
    return blocks, succ


def dominators(succ):
    n = len(succ)
    preds = [[] for _ in range(n)]
    for u, ss in enumerate(succ):
        for v in ss:
            preds[v].append(u)
    order, seen, stack = [], {0}, [(0, iter(succ[0]))]
    while stack:   # reverse post-order from the entry
        u, it = stack[-1]
        nxt = next(it, None)
        if nxt is None:
            order.append(u)
            stack.pop()
        elif nxt not in seen:
            seen.add(nxt)
            stack.append((nxt, iter(succ[nxt])))
    order.reverse()
    idx = {u: i for i, u in enumerate(order)}
    dom = {0: 0}
    changed = True
    while changed:   # Cooper, Harvey and Kennedy
        changed = False
        for u in order[1:]:
            ps = [p for p in preds[u] if p in dom]
            if not ps:
                continue
            new = ps[0]
            for p in ps[1:]:
                a, b = p, new
                while a != b:
                    while idx[a] > idx[b]:
                        a = dom[a]
                    while idx[b] > idx[a]:
                        b = dom[b]
                new = a
            if dom.get(u) != new:
                dom[u] = new
                changed = True
    return dom, preds


def dominates(dom, a, b):
    while True:
        if a == b:
            return True
        if b == 0 or b not in dom:
            return False
        b = dom[b]


def loops(succ):
    """Natural loops, by head: {head: set of blocks}."""
    dom, preds = dominators(succ)
    out = {}
    for u, ss in enumerate(succ):
        if u not in dom:
            continue
        for h in ss:
            if dominates(dom, h, u):   # u -> h is a back edge
                body, work = {h, u}, [u]
                while work:
                    x = work.pop()
                    if x == h:
                        continue
                    for p in preds[x]:
                        if p not in body and p in dom:
                            body.add(p)
                            work.append(p)
                out.setdefault(h, set()).update(body)
    return out, dom


def trips(blocks, succ, head, body, dom):
    """Shortest and longest trip from the head back to it (inner back edges dropped), with stack references."""
    cost = {b: len(blocks[b][1]) for b in body}
    sp = {b: sum(1 for t, _ in blocks[b][1] if '[sp' in t) for b in body}
    best = {}   # block -> (min, max, sp on the max)

    def edges(u):
        return [v for v in succ[u] if v in body and v != head and not dominates(dom, v, u)]

    order, seen = [], set()

    def visit(u):
        seen.add(u)
        for v in edges(u):
            if v not in seen:
                visit(v)
        order.append(u)

    sys.setrecursionlimit(100000)
    visit(head)
    order.reverse()
    best[head] = (cost[head], cost[head], sp[head])
    lo, hi, hisp = None, None, 0
    for u in order:
        if u not in best:
            continue
        mn, mx, s = best[u]
        if head in succ[u]:   # a latch: the trip ends here
            lo = mn if lo is None else min(lo, mn)
            if hi is None or mx > hi:
                hi, hisp = mx, s
        for v in edges(u):
            c = (mn + cost[v], mx + cost[v], s + sp[v])
            if v not in best:
                best[v] = c
            else:
                o = best[v]
                best[v] = (min(o[0], c[0]), max(o[1], c[1]), c[2] if c[1] > o[1] else o[2])
    size = sum(cost.values())
    allsp = sum(sp.values())
    return lo or 0, hi or 0, hisp, size, allsp


def innermost(lps, blks):
    """The loops holding any of blks with no smaller loop inside them holding any."""
    holding = [(h, b) for h, b in lps.items() if b & blks]
    out = []
    for h, b in holding:
        if not any(h2 != h and b2 < b and (b2 & blks) for h2, b2 in holding):
            out.append((h, b))
    return out


def main():
    args = sys.argv[1:]
    funcs_wanted, extra, show = [], [], []
    while '--show' in args:
        i = args.index('--show')
        show.append(args[i + 1])
        del args[i:i + 2]
    while '--mark' in args:
        i = args.index('--mark')
        extra.append(args[i + 1])
        del args[i:i + 2]
    if '--funcs' in args:
        i = args.index('--funcs')
        funcs_wanted = args[i + 1:]
        args = args[:i]
    src, asm = args
    ms = marks(src, extra)
    fs = functions(asm, src.split('/')[-1])
    cache = {}
    rows, over, shown = [], [], []
    for fname, body in fs.items():
        lines = {line for kind, _, line in body if kind == 'insn'}
        wanted = [(name, n, opts) for name, n, opts in ms if n in lines]
        if fname in funcs_wanted:
            wanted.append(('*', None, {}))
        if not wanted:
            continue
        if fname not in cache:
            blocks, succ = blocks_of(body)
            lps, dom = loops(succ)
            cache[fname] = (blocks, succ, lps, dom)
        blocks, succ, lps, dom = cache[fname]
        for name, n, opts in wanted:
            if n is None:   # every innermost loop of the function
                found = [(h, b) for h, b in lps.items() if not any(h2 != h and b2 < b for h2, b2 in lps.items())]
            else:
                blks = {i for i, (_, insns, _, _) in enumerate(blocks) if any(l == n for _, l in insns)}
                found = innermost(lps, blks)
            for k, (h, b) in enumerate(sorted(found)):
                lo, hi, hisp, size, allsp = trips(blocks, succ, h, b, dom)
                srcl = sorted({l for i in b for _, l in blocks[i][1] if l > 0})
                label = name if n is not None else 'loop'
                where = fname + ('' if len(found) == 1 else ' #%d' % (k + 1))
                if n is None:
                    where += ' (lines %d-%d)' % (srcl[0], srcl[-1]) if srcl else ''
                rows.append((label, where, opts.get('per', 'trip'), lo, hi, hisp, size, allsp, opts))
                if label in show:
                    shown.append('%s in %s:' % (label, where))
                    for i in sorted(b):
                        for l in blocks[i][0]:
                            shown.append(l + ':')
                        shown.extend('\t%s\t@ %d' % (t, ln) for t, ln in blocks[i][1])
                bad = []
                if 'max' in opts and hi > int(opts['max']):
                    bad.append('%d > max %s' % (hi, opts['max']))
                if 'sp' in opts and hisp > int(opts['sp']):
                    bad.append('%d stack > sp %s' % (hisp, opts['sp']))
                if bad:
                    over.append('%s in %s: %s' % (label, where, ', '.join(bad)))
    missing = [name for name, n, opts in ms if not any(r[0] == name for r in rows)]
    print('%-12s %-34s %-10s %9s %9s %6s %7s' % ('loop', 'in', 'a trip', 'shortest', 'longest', 'stack', 'size'))
    for label, where, per, lo, hi, hisp, size, allsp, opts in rows:
        budget = '' if 'max' not in opts else '  (max %s%s)' % (opts['max'], ', sp %s' % opts['sp'] if 'sp' in opts else '')
        print('%-12s %-34s %-10s %9d %9d %6d %7d%s' % (label, where, per, lo, hi, hisp, size, budget))
    for line in shown:
        print(line)
    for name in missing:
        print('%-12s not found (inlined away, or the marked line made no code)' % name)
    for o in over:
        print('OVER BUDGET: ' + o)
    sys.exit(1 if over or missing else 0)


if __name__ == '__main__':
    main()
