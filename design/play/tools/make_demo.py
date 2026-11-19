"""Writes fictional demo PDFs for store screenshots, using only the standard 14 fonts."""
import sys, os

def esc(s): return s.replace('\\', '\\\\').replace('(', '\\(').replace(')', '\\)')

class Page:
    def __init__(self): self.ops = []
    def text(self, x, y, s, size=11, bold=False, gray=0.1):
        f = 'F2' if bold else 'F1'
        self.ops.append(f'{gray} g BT /{f} {size} Tf {x} {y} Td ({esc(s)}) Tj ET')
    def rect(self, x, y, w, h, stroke=0.55, fill=None, lw=0.8):
        if fill is not None:
            self.ops.append(f'{fill} g {x} {y} {w} {h} re f')
        if stroke is not None:
            self.ops.append(f'{stroke} G {lw} w {x} {y} {w} {h} re S')
    def line(self, x1, y1, x2, y2, gray=0.6, lw=0.8):
        self.ops.append(f'{gray} G {lw} w {x1} {y1} m {x2} {y2} l S')
    def fillrgb(self, x, y, w, h, r, g, b):
        self.ops.append(f'{r} {g} {b} rg {x} {y} {w} {h} re f')

def write(path, pages, title):
    objs = []
    def add(b): objs.append(b); return len(objs)
    f1 = add(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>')
    f2 = add(b'<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica-Bold /Encoding /WinAnsiEncoding >>')
    pages_id = len(objs) + 1; objs.append(None)
    kids = []
    for p in pages:
        data = '\n'.join(p.ops).encode('cp1252')
        c = add(b'<< /Length %d >>\nstream\n' % len(data) + data + b'\nendstream')
        kids.append(add(b'<< /Type /Page /Parent %d 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 %d 0 R /F2 %d 0 R >> >> /Contents %d 0 R >>' % (pages_id, f1, f2, c)))
    objs[pages_id - 1] = b'<< /Type /Pages /Kids [%s] /Count %d >>' % (b' '.join(b'%d 0 R' % k for k in kids), len(kids))
    info = add(b'<< /Title (%s) /Producer (demo) >>' % esc(title).encode())
    cat = add(b'<< /Type /Catalog /Pages %d 0 R >>' % pages_id)
    out = bytearray(b'%PDF-1.4\n%\xe2\xe3\xcf\xd3\n'); offs = []
    for i, o in enumerate(objs, 1):
        offs.append(len(out)); out += b'%d 0 obj\n' % i + o + b'\nendobj\n'
    x = len(out)
    out += b'xref\n0 %d\n0000000000 65535 f \n' % (len(objs) + 1)
    for o in offs: out += b'%010d 00000 n \n' % o
    out += b'trailer\n<< /Size %d /Root %d 0 R /Info %d 0 R >>\nstartxref\n%d\n%%%%EOF\n' % (len(objs) + 1, cat, info, x)
    open(path, 'wb').write(out)

def statement():
    p = Page()
    p.fillrgb(0, 742, 612, 50, 0.08, 0.27, 0.23)
    p.text(48, 760, 'Harbor Bay Credit Union', 18, True, 1)
    p.text(430, 760, 'Account statement', 12, False, 1)
    p.text(48, 700, 'Alex Morgan', 12, True)
    p.text(48, 684, '28 Linden Street, Apt 4', 11)
    p.text(48, 668, 'Fairview, OR 97024', 11)
    p.text(360, 700, 'Statement period', 10, False, 0.4)
    p.text(360, 686, '1 Oct 2026 - 31 Oct 2026', 11)
    p.rect(48, 600, 516, 46, 0.75, 0.96)
    p.text(60, 628, 'Account number', 10, False, 0.4)
    p.text(60, 610, '4417 2093 8865 1120', 14, True)
    p.text(330, 628, 'Routing number', 10, False, 0.4)
    p.text(330, 610, '321 174 055', 14, True)
    p.text(48, 566, 'Summary', 13, True)
    rows = [('Opening balance', '2,418.62'), ('Deposits and credits', '3,950.00'), ('Withdrawals and debits', '-2,177.41'), ('Closing balance', '4,191.21')]
    y = 544
    for a, b in rows:
        p.text(48, y, a, 11); p.text(500, y, b, 11, a == 'Closing balance'); p.line(48, y - 6, 564, y - 6, 0.85); y -= 22
    p.text(48, 440, 'Transactions', 13, True)
    tx = [('02 Oct', 'Payroll deposit - Fairview Schools', '1,975.00'), ('05 Oct', 'Rent - Linden Street Properties', '-1,350.00'),
          ('09 Oct', 'Grocery - Market Hall', '-86.34'), ('14 Oct', 'Electric utility', '-71.20'), ('16 Oct', 'Payroll deposit - Fairview Schools', '1,975.00'),
          ('21 Oct', 'Pharmacy', '-23.87'), ('24 Oct', 'Phone bill', '-45.00'), ('29 Oct', 'Transfer to savings', '-601.00')]
    y = 418
    p.text(48, y, 'Date', 10, True, 0.4); p.text(110, y, 'Description', 10, True, 0.4); p.text(500, y, 'Amount', 10, True, 0.4); y -= 20
    for d, s, a in tx:
        p.text(48, y, d, 11); p.text(110, y, s, 11); p.text(500, y, a, 11); p.line(48, y - 6, 564, y - 6, 0.88); y -= 22
    p.text(48, 120, 'Questions? Visit any branch or call the number on the back of your card.', 9, False, 0.45)
    p.text(48, 106, 'This is a fictional sample document created for demonstration.', 9, False, 0.45)
    return [p]

def form():
    p = Page()
    p.text(48, 740, 'Volunteer Registration Form', 20, True)
    p.text(48, 718, 'Riverside Community Garden', 12, False, 0.4)
    p.line(48, 706, 564, 706, 0.3, 1.2)
    def field(label, x, y, w):
        p.text(x, y + 30, label, 9, False, 0.4); p.rect(x, y, w, 24, 0.55)
    p.text(48, 676, 'About you', 13, True)
    field('Full name', 48, 630, 330); field('Date of birth', 392, 630, 172)
    field('Street address', 48, 576, 516)
    field('City', 48, 522, 250); field('State', 312, 522, 90); field('ZIP code', 416, 522, 148)
    field('Phone', 48, 468, 250); field('Email', 312, 468, 252)
    p.text(48, 430, 'Availability', 13, True)
    for i, d in enumerate(['Weekday mornings', 'Weekday evenings', 'Saturdays', 'Sundays']):
        x = 48 + i * 130; p.rect(x, 400, 12, 12, 0.45); p.text(x + 18, 402, d, 10)
    p.text(48, 362, 'Emergency contact', 13, True)
    field('Name', 48, 316, 250); field('Phone', 312, 316, 252)
    p.text(48, 278, 'Agreement', 13, True)
    p.text(48, 260, 'I agree to follow the garden guidelines and safety rules, and I understand that', 10)
    p.text(48, 246, 'volunteering is unpaid. I consent to being contacted about volunteer shifts.', 10)
    p.line(48, 180, 330, 180, 0.4); p.text(48, 166, 'Signature', 9, False, 0.4)
    p.line(392, 180, 564, 180, 0.4); p.text(392, 166, 'Date', 9, False, 0.4)
    p.text(48, 90, 'Fictional sample form created for demonstration.', 9, False, 0.45)
    return [p]

LOREM = ("The community garden expansion adds twelve raised beds, a tool shed and a rainwater collection system. "
         "Work is planned in three phases so the existing plots stay open throughout the season. Volunteers will "
         "help with soil preparation, planting and weekly maintenance, supported by a part-time coordinator. "
         "Costs are covered by a city grant, member fees and local sponsors, with a small reserve for repairs.").split()

def para(p, x, y, width_chars, lines, size=10.5, seed=0):
    words = LOREM[seed % len(LOREM):] + LOREM
    i = 0
    for n in range(lines):
        line = ''
        while len(line) + len(words[i % len(words)]) < width_chars:
            line += words[i % len(words)] + ' '; i += 1
        p.text(x, y - n * 15, line.strip(), size);
    return y - lines * 15

def proposal():
    pages = []
    titles = [('Garden Expansion Proposal', 'Riverside Community Garden  |  November 2026'), ('Budget', 'Phase costs and funding'), ('Timeline', 'Spring to autumn 2027')]
    for k, (t, s) in enumerate(titles):
        p = Page()
        p.fillrgb(48, 700, 6, 56, 0.89, 0.69, 0.29)
        p.text(66, 736, t, 22 if k == 0 else 20, True)
        p.text(66, 714, s, 11, False, 0.4)
        y = 660
        for sec in range(3):
            p.text(48, y, ['Overview', 'Goals', 'Next steps', 'Costs', 'Funding', 'Reserve', 'Phase 1', 'Phase 2', 'Phase 3'][k * 3 + sec], 13, True)
            y = para(p, 48, y - 20, 92, 5, seed=k * 7 + sec * 13) - 22
        if k == 1:
            for r, (a, b) in enumerate([('Raised beds (12)', '$4,800'), ('Tool shed', '$3,200'), ('Rainwater system', '$2,650'), ('Total', '$10,650')]):
                p.text(60, y - r * 22, a, 11, a == 'Total'); p.text(470, y - r * 22, b, 11, a == 'Total'); p.line(48, y - r * 22 - 6, 564, y - r * 22 - 6, 0.85)
        p.text(520, 40, f'{k + 1} / 3', 9, False, 0.5)
        pages.append(p)
    return pages

def invoice():
    p = Page()
    p.text(48, 736, 'INVOICE', 24, True)
    p.text(48, 712, 'Greenleaf Supply Co.', 12, False, 0.35)
    p.text(400, 736, 'Invoice 1042', 12, True); p.text(400, 720, 'Date: 4 Nov 2026', 10, False, 0.4); p.text(400, 706, 'Due: 4 Dec 2026', 10, False, 0.4)
    p.text(48, 664, 'Bill to', 10, True, 0.4); p.text(48, 648, 'Riverside Community Garden', 11); p.text(48, 634, '400 River Road, Fairview, OR', 11)
    y = 590; p.fillrgb(48, y - 6, 516, 22, 0.93, 0.92, 0.88)
    p.text(56, y, 'Item', 10, True); p.text(340, y, 'Qty', 10, True); p.text(400, y, 'Price', 10, True); p.text(490, y, 'Amount', 10, True)
    items = [('Cedar raised bed kit', '12', '$320.00', '$3,840.00'), ('Organic soil mix (1 yd)', '8', '$95.00', '$760.00'), ('Drip irrigation set', '4', '$50.00', '$200.00')]
    for i, it in enumerate(items):
        yy = y - 26 - i * 24
        p.text(56, yy, it[0], 11); p.text(345, yy, it[1], 11); p.text(400, yy, it[2], 11); p.text(490, yy, it[3], 11); p.line(48, yy - 8, 564, yy - 8, 0.88)
    p.text(400, 470, 'Total', 12, True); p.text(490, 470, '$4,800.00', 12, True)
    p.text(48, 120, 'Fictional sample document created for demonstration.', 9, False, 0.45)
    return [p]

out = sys.argv[1]
write(os.path.join(out, 'Garden proposal.pdf'), proposal(), 'Garden proposal')
write(os.path.join(out, 'Invoice 1042.pdf'), invoice(), 'Invoice 1042')
os.makedirs(out, exist_ok=True)
write(os.path.join(out, 'Bank statement.pdf'), statement(), 'Bank statement')
write(os.path.join(out, 'Volunteer form.pdf'), form(), 'Volunteer form')
print('ok')
