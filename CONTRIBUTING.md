# Contributing to HCL Roles

Thank you for your interest in contributing. This document explains the
contribution process and expectations for the `startcloud.hcl_roles`
Ansible collection.

## Scope

HCL Roles is a collection of Ansible roles that install and configure HCL
products — Domino, Verse, Traveler, Nomad, Leap, SafeLinx, Sametime and
Volt MX. It follows the `startcloud.startcloud_roles` conventions and is
consumed by provisioner packages that vendor it under
`provisioners/ansible_collections/`.

## Prerequisites

- ansible-core 2.15 or later
- ansible-lint (the repo's `.ansible-lint` pins the `production` profile)
- Git

## Getting Started

1. Clone the repository.
2. Install lint tooling: `pip install ansible-lint`
3. Install collection dependencies: `ansible-galaxy collection install -r requirements.yml`
4. Run the linter from the repo root: `ansible-lint` — it must pass with zero
   failures and zero warnings.

## Role Conventions

Every role follows the `example_role` anatomy from `startcloud_roles`:

- `defaults/main.yml` — starts with `run_tasks: true` and the variable
  precedence comment; all defaults live here.
- `tasks/main.yml` — a single `when: run_tasks` block; every module by FQCN
  (`ansible.builtin.*`, `ansible.posix.*`, `community.general.*`); task names
  quoted and in gerund form ("Creating…", "Installing…", "Configuring…").
- `meta/main.yml` — full `galaxy_info` (role_name matching the folder,
  author, description, company, issue_tracker_url, SPDX license,
  min_ansible_version "2.15", platforms, tags) plus the `collections:` entry.
- `meta/argument_specs.yml` — every variable documented with type, default
  and description.
- `README.md` and `LICENSE.md` in the role root.
- Booleans are `true`/`false` (never yes/no); file modes are quoted strings
  (`"0755"`); commands declare `changed_when`.

Secrets never land in tracked files — real values live in the gitignored
root `vault.yml`, tracked files carry dummies or vault-var lookups, and
`galaxy.yml` `build_ignore` keeps `vault.yml` out of release artifacts.

## Testing

Roles with a `molecule/` directory carry container-backed tests.
Prerequisites: Docker plus `pip install molecule "molecule-plugins[docker]"`.
Run from the role directory:

```bash
cd roles/domino_install && molecule test
```

CI runs every scenario through the Molecule workflow's role matrix
(`.github/workflows/molecule.yml`) on pull requests — add your role to the
matrix when you add a scenario. The matrix is empty today; no role in this
collection has a container-honest scenario yet, and the HCL installers
require licensed media that cannot be fetched in CI.

## Submitting a Change

1. Create a feature branch from `main`.
2. Make your change following the conventions above.
3. Run `ansible-lint` — zero failures, zero warnings.
4. Update `meta/argument_specs.yml` and the role README when variables or
   behavior change.
5. Write commit messages following
   [Conventional Commits](https://www.conventionalcommits.org/): `feat:`,
   `fix:`, `docs:`, `refactor:`, `chore:`. release-please builds the version
   and CHANGELOG from them.
6. Push your branch and open a pull request; fill out the PR template.

## Adding a New Role

1. Copy the anatomy of an existing role (e.g. `domino_install`).
2. Check `startcloud.startcloud_roles` first — if a role there already covers
   the need, use it instead of duplicating; if a role here overlaps one
   there, note the subsumption in this role's README.
3. Add the role to the collection README's role list if one exists.
4. Run `ansible-lint` and open a PR.

## Releases

release-please manages versioning on `main`: merging its release PR stamps
`galaxy.yml`, tags a release, and the build workflow uploads
`startcloud-hcl_roles-<version>.tar.gz` (+ latest alias + `.sha256`
sidecars). Consumers install from the release artifact — never via git
submodule.

## Reporting Issues

- Search existing issues first.
- Use the appropriate issue template (bug, feature request, question).
- Include: role name, collection version, target OS, and relevant task
  output (with secrets redacted).

## Code of Conduct

By participating, you agree to the [Code of Conduct](CODE_OF_CONDUCT.md).

## License

By contributing, you agree that your contributions are licensed under the
[GPL-2.0-or-later](LICENSE.md).
