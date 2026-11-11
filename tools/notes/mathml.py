"""A deliberately small TeX-to-native-MathML renderer, with visible errors.

No JavaScript, CDN, shell calls, or third-party packages. Unsupported TeX is
preserved in an merror rather than silently losing a symbol or changing a formula.
"""

import html
import re


class MathError(ValueError):
    pass


SYMBOLS = dict(zip(
    "alpha beta gamma delta epsilon varepsilon zeta eta theta vartheta iota kappa lambda mu nu xi omicron pi varpi rho varrho sigma varsigma tau upsilon phi varphi chi psi omega Gamma Delta Theta Lambda Xi Pi Sigma Upsilon Phi Psi Omega".split(),
    "α β γ δ ϵ ε ζ η θ ϑ ι κ λ μ ν ξ ο π ϖ ρ ϱ σ ς τ υ ϕ φ χ ψ ω Γ Δ Θ Λ Ξ Π Σ Υ Φ Ψ Ω".split(),
))
SYMBOLS.update(dict(zip(
    "times cdot div pm mp le leq ge geq ne neq approx sim simeq equiv in notin subset subseteq supset supseteq cup cap to rightarrow leftarrow leftrightarrow Rightarrow Leftarrow Leftrightarrow implies iff mapsto infty partial nabla sum prod int iint oint forall exists neg land lor perp parallel propto ell ldots cdots dots degree prime mid vert Vert lbrace rbrace langle rangle".split(),
    "× · ÷ ± ∓ ≤ ≤ ≥ ≥ ≠ ≠ ≈ ∼ ≃ ≡ ∈ ∉ ⊂ ⊆ ⊃ ⊇ ∪ ∩ → → ← ↔ ⇒ ⇐ ⇔ ⇒ ⇔ ↦ ∞ ∂ ∇ ∑ ∏ ∫ ∬ ∮ ∀ ∃ ¬ ∧ ∨ ⊥ ∥ ∝ ℓ … ⋯ … ° ′ ∣ | ‖ { } ⟨ ⟩".split(),
)))
FUNCTIONS = set("sin cos tan log ln exp min max lim sup inf det Pr mod".split())


def tag(name, value, **attrs):
    attributes = ''.join(f' {k}="{html.escape(str(v), quote=True)}"' for k, v in attrs.items())
    return f"<{name}{attributes}>{value}</{name}>"


class Parser:
    def __init__(self, text):
        if len(text) > 12000:
            raise MathError("公式过长")
        self.text, self.pos, self.depth = text, 0, 0

    def skip(self):
        while self.pos < len(self.text) and self.text[self.pos].isspace():
            self.pos += 1

    def group(self):
        self.skip()
        if self.pos >= len(self.text):
            raise MathError("缺少公式参数")
        if self.text[self.pos] == '{':
            self.pos += 1
            return tag('mrow', self.sequence(True))
        return self.atom(single=True)

    def raw_group(self):
        self.skip()
        if self.pos >= len(self.text) or self.text[self.pos] != '{':
            raise MathError("文本参数需要花括号")
        self.pos += 1
        start, level = self.pos, 1
        while self.pos < len(self.text):
            char = self.text[self.pos]
            level += (char == '{') - (char == '}')
            if not level:
                result = self.text[start:self.pos]
                self.pos += 1
                return result
            self.pos += 1
        raise MathError("花括号未闭合")

    def atom(self, single=False):
        self.skip()
        if self.pos >= len(self.text):
            raise MathError("缺少操作数")
        char = self.text[self.pos]
        if char == '{':
            return self.group()
        self.pos += 1
        if char == '\\':
            match = re.match(r'[A-Za-z]+|.', self.text[self.pos:])
            if not match:
                raise MathError("不完整的 TeX 命令")
            command = match[0]
            self.pos += len(command)
            if command in SYMBOLS:
                return tag('mi' if command in set(list(SYMBOLS)[:40]) else 'mo', html.escape(SYMBOLS[command]))
            if command in FUNCTIONS:
                return tag('mi', command, mathvariant='normal')
            if command in ('frac', 'dfrac', 'tfrac'):
                return tag('mfrac', self.group() + self.group())
            if command == 'sqrt':
                self.skip()
                index = None
                if self.pos < len(self.text) and self.text[self.pos] == '[':
                    end = self.text.find(']', self.pos)
                    if end < 0:
                        raise MathError("根指数未闭合")
                    index = Parser(self.text[self.pos + 1:end]).sequence()
                    self.pos = end + 1
                value = self.group()
                return tag('mroot', value + tag('mrow', index)) if index is not None else tag('msqrt', value)
            if command in ('text', 'textrm', 'mbox', 'operatorname'):
                return tag('mtext', html.escape(self.raw_group()))
            if command in ('mathrm', 'mathbf', 'mathit', 'mathcal', 'mathbb', 'boldsymbol'):
                variant = {'mathrm': 'normal', 'mathbf': 'bold', 'mathit': 'italic', 'mathcal': 'script', 'mathbb': 'double-struck', 'boldsymbol': 'bold'}[command]
                return tag('mstyle', self.group(), mathvariant=variant)
            if command in ('bar', 'overline', 'hat', 'widehat', 'vec', 'dot', 'ddot', 'tilde'):
                accent = {'bar': '¯', 'overline': '¯', 'hat': '^', 'widehat': '^', 'vec': '→', 'dot': '˙', 'ddot': '¨', 'tilde': '~'}[command]
                return tag('mover', self.group() + tag('mo', accent), accent='true')
            if command in ('left', 'right', 'big', 'Big', 'bigl', 'bigr', 'displaystyle', 'textstyle', 'limits', 'nolimits'):
                # Delimiters themselves are consumed as ordinary atoms.
                self.skip()
                if command in ('left', 'right') and self.text[self.pos:self.pos + 1] == '.':
                    self.pos += 1
                return ''
            if command in (',', ';', ':', '!', ' ', 'quad', 'qquad'):
                return '<mspace width="0.25em"/>'
            if command in ('{', '}', '%', '$', '#', '_', '&', '|'):
                return tag('mo', html.escape(command))
            raise MathError(f"未支持 \\{command}")
        if char in '}_^':
            raise MathError(f"意外符号 {char}")
        if char.isdigit():
            if single:
                return tag('mn', char)
            match = re.match(r'[0-9]*(?:\.[0-9]+)?', self.text[self.pos:])
            self.pos += len(match[0])
            return tag('mn', char + match[0])
        if '\u3400' <= char <= '\u9fff':
            return tag('mtext', html.escape(char))
        return tag('mi' if char.isalpha() else 'mo', html.escape(char))

    def sequence(self, grouped=False):
        self.depth += 1
        if self.depth > 60:
            raise MathError("公式嵌套过深")
        result = []
        while True:
            self.skip()
            if self.pos >= len(self.text):
                if grouped:
                    raise MathError("花括号未闭合")
                break
            if self.text[self.pos] == '}':
                if not grouped:
                    raise MathError("多余的右花括号")
                self.pos += 1
                break
            base = self.atom()
            scripts = {}
            self.skip()
            while self.pos < len(self.text) and self.text[self.pos] in '_^':
                kind = self.text[self.pos]
                self.pos += 1
                if kind in scripts:
                    raise MathError("重复上下标")
                scripts[kind] = self.group()
                self.skip()
            if len(scripts) == 2:
                base = tag('msubsup', base + scripts['_'] + scripts['^'])
            elif scripts:
                kind, script = next(iter(scripts.items()))
                base = tag('msub' if kind == '_' else 'msup', base + script)
            result.append(base)
        self.depth -= 1
        return ''.join(result)


def mathml(tex, display=False):
    """Return standalone native MathML; raise MathError on unsupported syntax."""
    # Normalize common Unicode scripts without changing their mathematical value.
    tex = tex.translate(str.maketrans({'₀': '_0', '₁': '_1', '₂': '_2', '₃': '_3', '²': '^2', '³': '^3', '−': '-'}))
    # Plain Unicode lecture notes sometimes use ^(t-t0), rather than TeX braces.
    # Preserve those explicit parentheses as one exponent, including nested pairs.
    def brace_exponents(value):
        start = value.find('^(')
        while start >= 0:
            level, end = 1, start + 2
            while end < len(value) and level:
                level += (value[end] == '(') - (value[end] == ')')
                end += 1
            if level:
                raise MathError('指数括号未闭合')
            value = value[:start + 1] + '{' + value[start + 1:end] + '}' + value[end:]
            start = value.find('^(', end + 2)
        return value
    tex = brace_exponents(tex)
    body = Parser(tex).sequence()
    annotation = tag('annotation', html.escape(tex), encoding='application/x-tex')
    return tag('math', tag('semantics', tag('mrow', body) + annotation), xmlns='http://www.w3.org/1998/Math/MathML', display='block' if display else 'inline')
