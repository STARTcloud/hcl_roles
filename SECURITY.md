# Security Policy

## Reporting a Vulnerability

**Please do not report security vulnerabilities through public GitHub issues.**

If you discover a security vulnerability in HCL Roles, please report it
responsibly:

### Preferred Method: Security Advisory

1. Go to the [GitHub Security Advisory page](https://github.com/STARTcloud/hcl_roles/security/advisories)
2. Click "Report a vulnerability"
3. Fill out the advisory form with detailed information
4. Submit the advisory

### What to Include

- **Description** of the vulnerability
- **Steps to reproduce** the issue
- **Potential impact** of the vulnerability
- **Affected role(s)** and collection version
- **Suggested fix** (if you have one)

## Response Process

Due to limited development resources, please understand that:

- **Initial Response**: we aim to acknowledge receipt within 48–72 hours
- **Assessment**: initial assessment within about a week
- **Resolution**: timeline depends on severity, typically 1–4 weeks
- **Disclosure**: coordinated disclosure after a fix is available

## Focus Areas for an Ansible Collection

Roles in this collection run with real privileges on provisioned machines.
The security-relevant areas are:

- **No secrets in tracked files or artifacts** — real credentials live only
  in the gitignored root `vault.yml`; tracked files carry dummies or
  vault-var lookups, and `galaxy.yml` `build_ignore` keeps `vault.yml` out of
  every release artifact. A committed credential is a vulnerability — report
  it.
- **Release artifact integrity** — releases ship `.sha256` sidecars;
  consumers should verify after download and pin immutable versioned
  artifacts, never the mutable latest alias.
- **Download verification** — roles that fetch remote artifacts should pin
  checksums or fingerprints; HCL installer media is licensed and must never
  be committed or republished.
- **Privilege boundaries** — service users are unprivileged; sudoers grants
  are narrow single-command entries; restricted shells (`rbash`) where
  applicable.

## Best Practices for Consumers

1. **Verify checksums** on downloaded release artifacts.
2. **Pin versions** in `requirements.yml`.
3. **Supply secrets at runtime** (`-e @vault.yml`, ansible-vault, or your
   provisioner's secrets mechanism) — never commit them.

## Updates to This Policy

This security policy may be updated as the project evolves. Check back
periodically for changes.
