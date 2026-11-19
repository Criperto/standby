use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::{LazyLock, Mutex};

#[derive(Debug, Clone, PartialEq)]
pub enum ExprVal {
    Num(f64),
    Str(String),
    Bool(bool),
}

impl ExprVal {
    pub fn as_num(&self) -> f64 {
        match self {
            ExprVal::Num(n) => {
                if n.is_finite() {
                    *n
                } else {
                    0.0
                }
            }
            ExprVal::Bool(b) => {
                if *b {
                    1.0
                } else {
                    0.0
                }
            }
            ExprVal::Str(s) => s
                .trim()
                .parse::<f64>()
                .ok()
                .filter(|v| v.is_finite())
                .unwrap_or(0.0),
        }
    }

    pub fn as_bool(&self) -> bool {
        match self {
            ExprVal::Bool(b) => *b,
            ExprVal::Num(n) => n.is_finite() && *n != 0.0,
            ExprVal::Str(s) => !s.is_empty() && s != "0" && !s.eq_ignore_ascii_case("false"),
        }
    }

    pub fn as_str(&self) -> String {
        match self {
            ExprVal::Str(s) => s.clone(),
            ExprVal::Bool(b) => if *b { "true" } else { "false" }.to_string(),
            ExprVal::Num(n) => {
                if !n.is_finite() {
                    "0".to_string()
                } else if (n.fract()).abs() < 1e-12 {
                    format!("{}", *n as i64)
                } else {
                    format!("{n}")
                }
            }
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub enum TokenKind {
    Num(f64),
    Str(String),
    Bool(bool),
    Ident(String),
    Op(String),
    Punct(char),
    Eof,
}

#[derive(Debug, Clone, PartialEq)]
pub enum AstNode {
    Num(f64),
    Str(String),
    Bool(bool),
    Ident(String),
    Unary {
        op: char,
        expr: Box<AstNode>,
    },
    Binary {
        op: String,
        left: Box<AstNode>,
        right: Box<AstNode>,
    },
    Ternary {
        cond: Box<AstNode>,
        consequent: Box<AstNode>,
        alternate: Box<AstNode>,
    },
    Call {
        name: String,
        args: Vec<AstNode>,
    },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ChainedToken {
    pub expr: String,
    pub op: char,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct ExprDependencies {
    pub needs_frame_t: bool,
    pub needs_seconds: bool,
    pub needs_minutes: bool,
    pub needs_media_progress: bool,
    pub uses_regex: bool,
    pub referenced_vars: Vec<String>,
}

#[derive(Debug, thiserror::Error)]
pub enum ExprParseError {
    #[error("Empty expression")]
    Empty,
    #[error("Unexpected token '{0}'")]
    UnexpectedToken(String),
    #[error("Expected '{expected}' but found '{found}'")]
    ExpectedToken { expected: String, found: String },
}

pub fn tokenize(input: &str) -> Vec<TokenKind> {
    let bytes = input.as_bytes();
    let len = bytes.len();
    let mut i = 0usize;
    let mut tokens = Vec::new();

    while i < len {
        let b = bytes[i];
        let c = b as char;

        if matches!(c, ' ' | '\t' | '\r' | '\n') {
            i += 1;
            continue;
        }

        if c == '"' || c == '\'' {
            let quote = c;
            i += 1;
            let mut s = String::new();
            while i < len {
                let ch = input[i..].chars().next().unwrap();
                if ch == quote {
                    i += ch.len_utf8();
                    break;
                }
                if ch == '\\' && i + 1 < len {
                    i += 1;
                    let esc = input[i..].chars().next().unwrap();
                    match esc {
                        'n' => s.push('\n'),
                        'r' => s.push('\r'),
                        't' => s.push('\t'),
                        '\\' => s.push('\\'),
                        '"' => s.push('"'),
                        '\'' => s.push('\''),
                        other => {
                            s.push('\\');
                            s.push(other);
                        }
                    }
                    i += esc.len_utf8();
                } else {
                    s.push(ch);
                    i += ch.len_utf8();
                }
            }
            tokens.push(TokenKind::Str(s));
            continue;
        }

        if b.is_ascii_digit() || (c == '.' && i + 1 < len && bytes[i + 1].is_ascii_digit()) {
            let num_start = i;
            let mut has_dot = c == '.';
            i += 1;
            while i < len {
                let nb = bytes[i];
                if nb.is_ascii_digit() {
                    i += 1;
                } else if nb == b'.' && !has_dot {
                    has_dot = true;
                    i += 1;
                } else {
                    break;
                }
            }
            if i < len && (bytes[i] == b'e' || bytes[i] == b'E') {
                let e_idx = i;
                i += 1;
                if i < len && (bytes[i] == b'+' || bytes[i] == b'-') {
                    i += 1;
                }
                let digit_start = i;
                while i < len && bytes[i].is_ascii_digit() {
                    i += 1;
                }
                if i == digit_start {
                    i = e_idx;
                }
            }
            let num_str = &input[num_start..i];
            let val = num_str.parse::<f64>().unwrap_or(0.0);
            tokens.push(TokenKind::Num(val));
            continue;
        }

        if b.is_ascii_alphabetic() || c == '_' || c == '$' {
            let id_start = i;
            i += 1;
            while i < len {
                let nb = bytes[i];
                if nb.is_ascii_alphanumeric() || nb == b'_' || nb == b'$' || nb == b'.' {
                    i += 1;
                } else {
                    break;
                }
            }
            let ident = &input[id_start..i];
            if ident == "true" {
                tokens.push(TokenKind::Bool(true));
            } else if ident == "false" {
                tokens.push(TokenKind::Bool(false));
            } else {
                tokens.push(TokenKind::Ident(ident.to_string()));
            }
            continue;
        }

        if i + 1 < len {
            let two = &input[i..i + 2];
            if matches!(two, "&&" | "||" | "==" | "!=" | "<=" | ">=") {
                tokens.push(TokenKind::Op(two.to_string()));
                i += 2;
                continue;
            }
        }

        if "+-*/%!<>?:(),".contains(c) {
            if matches!(c, '(' | ')' | ',') {
                tokens.push(TokenKind::Punct(c));
            } else {
                tokens.push(TokenKind::Op(c.to_string()));
            }
            i += 1;
            continue;
        }

        let ch = input[i..].chars().next().unwrap();
        i += ch.len_utf8();
    }

    tokens.push(TokenKind::Eof);
    tokens
}

fn precedence(tok: &TokenKind) -> u8 {
    match tok {
        TokenKind::Op(op) => match op.as_str() {
            "?" | ":" => 10,
            "||" => 20,
            "&&" => 30,
            "==" | "!=" => 40,
            "<" | "<=" | ">" | ">=" => 50,
            "+" | "-" => 60,
            "*" | "/" | "%" => 70,
            _ => 0,
        },
        _ => 0,
    }
}

fn token_label(tok: &TokenKind) -> String {
    match tok {
        TokenKind::Num(n) => n.to_string(),
        TokenKind::Str(s) => format!("{s:?}"),
        TokenKind::Bool(b) => b.to_string(),
        TokenKind::Ident(s) => s.clone(),
        TokenKind::Op(s) => s.clone(),
        TokenKind::Punct(c) => c.to_string(),
        TokenKind::Eof => "<eof>".to_string(),
    }
}

struct Parser {
    tokens: Vec<TokenKind>,
    pos: usize,
}

impl Parser {
    fn peek(&self) -> &TokenKind {
        self.tokens.get(self.pos).unwrap_or(&TokenKind::Eof)
    }

    fn next(&mut self) -> TokenKind {
        let t = self.peek().clone();
        if self.pos < self.tokens.len() {
            self.pos += 1;
        }
        t
    }

    fn consume_punct_or_op(&mut self, expected: &str) -> Result<(), ExprParseError> {
        let tok = self.peek().clone();
        let matches = match &tok {
            TokenKind::Punct(c) => expected.len() == 1 && expected.starts_with(*c),
            TokenKind::Op(op) => op == expected,
            _ => false,
        };
        if !matches {
            return Err(ExprParseError::ExpectedToken {
                expected: expected.to_string(),
                found: token_label(&tok),
            });
        }
        self.pos += 1;
        Ok(())
    }

    fn parse_expr(&mut self, min_prec: u8) -> Result<AstNode, ExprParseError> {
        let tok = self.next();
        let mut left = match tok {
            TokenKind::Num(n) => AstNode::Num(n),
            TokenKind::Str(s) => AstNode::Str(s),
            TokenKind::Bool(b) => AstNode::Bool(b),
            TokenKind::Ident(name) => {
                if matches!(self.peek(), TokenKind::Punct('(')) {
                    self.next();
                    let mut args = Vec::new();
                    if !matches!(self.peek(), TokenKind::Punct(')')) {
                        loop {
                            args.push(self.parse_expr(0)?);
                            if matches!(self.peek(), TokenKind::Punct(',')) {
                                self.next();
                            } else {
                                break;
                            }
                        }
                    }
                    self.consume_punct_or_op(")")?;
                    AstNode::Call { name, args }
                } else {
                    AstNode::Ident(name)
                }
            }
            TokenKind::Punct('(') => {
                let inner = self.parse_expr(0)?;
                self.consume_punct_or_op(")")?;
                inner
            }
            TokenKind::Op(ref op) if matches!(op.as_str(), "!" | "-" | "+") => {
                let op_ch = op.chars().next().unwrap();
                let rhs = self.parse_expr(80)?;
                AstNode::Unary {
                    op: op_ch,
                    expr: Box::new(rhs),
                }
            }
            other => return Err(ExprParseError::UnexpectedToken(token_label(&other))),
        };

        while self.pos < self.tokens.len() {
            let next_tok = self.peek().clone();
            if matches!(
                next_tok,
                TokenKind::Eof | TokenKind::Punct(')') | TokenKind::Punct(',')
            ) || matches!(&next_tok, TokenKind::Op(op) if op == ":")
            {
                break;
            }

            let next_prec = precedence(&next_tok);
            if next_prec <= min_prec {
                break;
            }

            let op_str = match self.next() {
                TokenKind::Op(s) => s,
                other => return Err(ExprParseError::UnexpectedToken(token_label(&other))),
            };

            if op_str == "?" {
                let cons = self.parse_expr(0)?;
                self.consume_punct_or_op(":")?;
                let alt = self.parse_expr(next_prec - 1)?;
                left = AstNode::Ternary {
                    cond: Box::new(left),
                    consequent: Box::new(cons),
                    alternate: Box::new(alt),
                };
            } else {
                let right = self.parse_expr(next_prec)?;
                left = AstNode::Binary {
                    op: op_str,
                    left: Box::new(left),
                    right: Box::new(right),
                };
            }
        }

        Ok(left)
    }
}

const MAX_AST_CACHE: usize = 2000;

struct AstCache {
    map: HashMap<String, AstNode>,
    order: VecDeque<String>,
}

impl AstCache {
    fn new() -> Self {
        Self {
            map: HashMap::new(),
            order: VecDeque::new(),
        }
    }

    fn get(&self, key: &str) -> Option<AstNode> {
        self.map.get(key).cloned()
    }

    fn insert(&mut self, key: String, val: AstNode) {
        if self.map.contains_key(&key) {
            return;
        }
        if self.map.len() >= MAX_AST_CACHE {
            if let Some(old) = self.order.pop_front() {
                self.map.remove(&old);
            }
        }
        self.order.push_back(key.clone());
        self.map.insert(key, val);
    }
}

static AST_CACHE: LazyLock<Mutex<AstCache>> = LazyLock::new(|| Mutex::new(AstCache::new()));

pub fn parse_ast(input: &str) -> Result<AstNode, ExprParseError> {
    let trimmed = input.trim();
    if trimmed.is_empty() {
        return Err(ExprParseError::Empty);
    }

    if let Ok(cache) = AST_CACHE.lock() {
        if let Some(hit) = cache.get(trimmed) {
            return Ok(hit);
        }
    }

    let tokens = tokenize(trimmed);
    let mut parser = Parser { tokens, pos: 0 };
    let ast = parser.parse_expr(0)?;
    if !matches!(parser.peek(), TokenKind::Eof) {
        return Err(ExprParseError::UnexpectedToken(token_label(parser.peek())));
    }

    if let Ok(mut cache) = AST_CACHE.lock() {
        cache.insert(trimmed.to_string(), ast.clone());
    }

    Ok(ast)
}

pub fn node_to_string(node: &AstNode) -> String {
    match node {
        AstNode::Num(n) => {
            if (n.fract()).abs() < 1e-12 && n.abs() < (i64::MAX as f64) {
                format!("{}", *n as i64)
            } else {
                format!("{n}")
            }
        }
        AstNode::Bool(b) => b.to_string(),
        AstNode::Str(s) => serde_json::to_string(s).unwrap_or_else(|_| format!("\"{s}\"")),
        AstNode::Ident(name) => name.clone(),
        AstNode::Unary { op, expr } => format!("{op}{}", node_to_string(expr)),
        AstNode::Binary { op, left, right } => {
            format!("{} {op} {}", node_to_string(left), node_to_string(right))
        }
        AstNode::Ternary {
            cond,
            consequent,
            alternate,
        } => format!(
            "{} ? {} : {}",
            node_to_string(cond),
            node_to_string(consequent),
            node_to_string(alternate)
        ),
        AstNode::Call { name, args } => {
            let arg_strs: Vec<String> = args.iter().map(node_to_string).collect();
            format!("{name}({})", arg_strs.join(", "))
        }
    }
}

pub fn is_cad_expr(input: &str) -> bool {
    let s = input.trim();
    let rest = if let Some(r) = s.strip_prefix("+=") {
        r
    } else if let Some(r) = s.strip_prefix("-=") {
        r
    } else if let Some(r) = s.strip_prefix("*=") {
        r
    } else if let Some(r) = s.strip_prefix("/=") {
        r
    } else if let Some(r) = s.strip_prefix(['+', '-', '*', '/']) {
        r
    } else {
        return false;
    };
    let rest = rest.trim_start();
    !rest.is_empty() && rest.chars().all(|c| c.is_ascii_digit() || c == '.') && rest.parse::<f64>().is_ok()
}

pub fn eval_cad(input: &str, current_val: f64) -> f64 {
    let s = input.trim();
    let (op, num_str) = if let Some(r) = s.strip_prefix("+=") {
        ("+=", r)
    } else if let Some(r) = s.strip_prefix("-=") {
        ("-=", r)
    } else if let Some(r) = s.strip_prefix("*=") {
        ("*=", r)
    } else if let Some(r) = s.strip_prefix("/=") {
        ("/=", r)
    } else if let Some(r) = s.strip_prefix('+') {
        ("+", r)
    } else if let Some(r) = s.strip_prefix('-') {
        ("-", r)
    } else if let Some(r) = s.strip_prefix('*') {
        ("*", r)
    } else if let Some(r) = s.strip_prefix('/') {
        ("/", r)
    } else {
        return current_val;
    };

    let Ok(n) = num_str.trim().parse::<f64>() else {
        return current_val;
    };
    if !n.is_finite() {
        return current_val;
    }

    match op {
        "+=" | "+" => current_val + n,
        "-=" | "-" => current_val - n,
        "*=" | "*" => current_val * n,
        "/=" | "/" => {
            if n != 0.0 {
                current_val / n
            } else {
                current_val
            }
        }
        _ => current_val,
    }
}

pub fn split_chained(expr: &str) -> Vec<ChainedToken> {
    let mut tokens = Vec::new();
    let mut depth = 0usize;
    let mut cur = String::new();
    let mut op = '+';
    let mut in_quote: Option<char> = None;
    let mut prev_backslash = false;

    for c in expr.chars() {
        if let Some(q) = in_quote {
            cur.push(c);
            if c == q && !prev_backslash {
                in_quote = None;
            }
            prev_backslash = c == '\\' && !prev_backslash;
            continue;
        }
        prev_backslash = false;

        match c {
            '"' | '\'' => {
                in_quote = Some(c);
                cur.push(c);
            }
            '(' => {
                depth += 1;
                cur.push(c);
            }
            ')' => {
                depth = depth.saturating_sub(1);
                cur.push(c);
            }
            ',' | '+' | '-' if depth == 0 => {
                let trimmed = cur.trim();
                if !trimmed.is_empty() {
                    tokens.push(ChainedToken {
                        expr: trimmed.to_string(),
                        op,
                    });
                    op = c;
                    cur.clear();
                } else if c == '-' && cur.trim().is_empty() {
                    cur.push(c);
                } else {
                    op = c;
                }
            }
            _ => cur.push(c),
        }
    }

    let trimmed = cur.trim();
    if !trimmed.is_empty() {
        tokens.push(ChainedToken {
            expr: trimmed.to_string(),
            op,
        });
    }
    tokens
}

pub fn analyze_dependencies(expr: &str) -> ExprDependencies {
    let mut deps = ExprDependencies::default();
    let mut seen_vars = HashSet::new();

    for chunk in split_chained(expr) {
        if let Ok(ast) = parse_ast(&chunk.expr) {
            walk_deps(&ast, &mut deps, &mut seen_vars);
        }
    }
    if deps.referenced_vars.is_empty() {
        if let Ok(ast) = parse_ast(expr) {
            walk_deps(&ast, &mut deps, &mut seen_vars);
        }
    }
    deps.referenced_vars = seen_vars.into_iter().collect();
    deps.referenced_vars.sort();
    deps
}

fn walk_deps(node: &AstNode, deps: &mut ExprDependencies, vars: &mut HashSet<String>) {
    match node {
        AstNode::Ident(name) => {
            vars.insert(name.clone());
            match name.as_str() {
                "t" | "millisecond" => deps.needs_frame_t = true,
                "second" => deps.needs_seconds = true,
                "minute" | "hour" => deps.needs_minutes = true,
                "mediaProgress" | "mediaPosition" | "mediaLength" => {
                    deps.needs_media_progress = true
                }
                _ => {}
            }
        }
        AstNode::Unary { expr, .. } => walk_deps(expr, deps, vars),
        AstNode::Binary { left, right, .. } => {
            walk_deps(left, deps, vars);
            walk_deps(right, deps, vars);
        }
        AstNode::Ternary {
            cond,
            consequent,
            alternate,
        } => {
            walk_deps(cond, deps, vars);
            walk_deps(consequent, deps, vars);
            walk_deps(alternate, deps, vars);
        }
        AstNode::Call { name, args } => {
            let lower = name.to_ascii_lowercase();
            match lower.as_str() {
                "spin" | "pulse" | "osc" | "wiggle" | "anim" | "morph" | "follow" | "once"
                | "intro" => {
                    deps.needs_frame_t = true;
                }
                "timesecond" => {
                    let smooth = args
                        .first()
                        .map(|a| matches!(a, AstNode::Bool(true)))
                        .unwrap_or(false);
                    if smooth {
                        deps.needs_frame_t = true;
                    } else {
                        deps.needs_seconds = true;
                    }
                }
                "timeminute" | "timehour" | "timehour24" => {
                    let smooth = args
                        .first()
                        .map(|a| matches!(a, AstNode::Bool(true)))
                        .unwrap_or(false);
                    if smooth {
                        deps.needs_seconds = true;
                    } else {
                        deps.needs_minutes = true;
                    }
                }
                "add" | "random" => deps.needs_seconds = true,
                "regexmatch" | "regextest" | "regexextract" | "regexreplace" => {
                    deps.uses_regex = true;
                }
                _ => {}
            }
            for a in args {
                walk_deps(a, deps, vars);
            }
        }
        _ => {}
    }
}
