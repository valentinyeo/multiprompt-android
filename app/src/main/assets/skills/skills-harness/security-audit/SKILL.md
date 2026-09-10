---
name: security-audit
description: "Security audit with Semgrep, CodeQL, SARIF parsing, variant analysis, supply chain risk, and differential review. Run automated vulnerability scanning on any codebase."
version: 1.0.0
---

# Security Audit

Comprehensive security auditing using Trail of Bits tools and workflows.

## Quick Start

```bash
semgrep --config auto .                    # Quick scan
semgrep --config p/owasp-top-ten .         # OWASP Top 10
codeql database create db --language=javascript  # CodeQL
```

## Available Sub-Skills

- `./skills/semgrep/SKILL.md` - Semgrep rule writing and scanning
- `./skills/semgrep-rule-creator/SKILL.md` - Custom Semgrep rule creation
- `./skills/codeql/SKILL.md` - CodeQL query writing and analysis
- `./skills/sarif-parsing/SKILL.md` - Parse SARIF output from any scanner
- `./skills/variant-analysis/SKILL.md` - Find variants of known vulnerabilities
- `./skills/supply-chain-risk-auditor/SKILL.md` - Dependency and supply chain risk
- `./skills/audit-context-building/SKILL.md` - Build audit context for code review
- `./skills/differential-review/SKILL.md` - Security-focused diff review

## When to Use

- **security audit** / **vulnerability scan** - Full codebase scan
- **supply chain** / **dependency audit** - Check dependencies
- **code review** with security focus - Differential review
- **OWASP** / **CVE** scanning - Targeted vulnerability detection

## Workflow

1. Run Semgrep with auto config for quick wins
2. Use variant analysis to find similar patterns
3. Check supply chain risks in dependencies
4. Parse SARIF results for structured reporting
5. Build audit context for deeper manual review

## Source

[Trail of Bits Skills](https://github.com/trailofbits/skills) - CC BY-SA 4.0
